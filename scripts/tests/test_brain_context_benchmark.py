"""Protect the context benchmark's evidence and failure accounting."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import brain_context_benchmark as benchmark


class BrainContextBenchmarkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = benchmark.load_fixture()
        cls.cases = {case['id']: case for case in cls.fixture['cases']}

    def example(self, case_id, limit, *, content=None):
        case = self.cases[case_id]
        restored = case['expected']['restored_order']
        context = None if content is None else {
            'context_bytes': 100, 'question_preserved': True,
            'remembered_slots': {'order_no': restored}, 'payload': {'messages': [{'content': content}]},
        }
        return case, {'restored_order': restored, 'order': restored, 'persisted_order': restored,
                      'turn_persisted': True, 'context': context, 'error': None, 'model_parse_ok': bool(context),
                      'intent': '物流查询', 'budget_bytes': limit, 'receipt': {}, 'latency_ms': 1}

    def test_frozen_digest_rejects_a_changed_case(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / benchmark.FIXTURE.name
            fixture.write_text('{}', encoding='utf-8')
            digest = benchmark.FIXTURE.with_suffix('.sha256').read_bytes()
            fixture.with_suffix('.sha256').write_bytes(digest)
            with self.assertRaisesRegex(ValueError, 'frozen version'):
                benchmark.load_fixture(fixture)

    def test_question_and_history_are_constructed_without_the_answer_label(self):
        case = self.cases['older-topic-behind-long-turn']
        noise = self.fixture['noise']
        question, history = benchmark.fixture_input(case, noise)
        self.assertEqual(case['question'], question)
        self.assertEqual(noise * 18, history[-1]['content'])
        self.assertEqual((question, history), benchmark.fixture_input(case | {'expected': None}, noise))

    def test_predeclared_loss_is_reported_only_as_allowed_for_the_small_budget(self):
        case, row = self.example('older-topic-behind-long-turn', 2048, content='缺少较早话题的提示词')
        small = benchmark.assess(case, 2048, row)
        large = benchmark.assess(case, 4096, row)
        self.assertEqual(['想确认款项进度'], small['missing_anchors'])
        self.assertEqual([], small['failures'])
        self.assertIn('required historical evidence lost', large['failures'])

    def test_excluded_memory_fails_if_it_enters_prompt_or_result(self):
        case, row = self.example('absent-local-memory', 2048, content='CTX-FOREIGN2')
        self.assertIn('foreign, neighboring or deleted session memory observed',
                      benchmark.assess(case, 2048, row)['failures'])
        case, row = self.example('deleted-session-memory', 2048, content='没有记忆')
        row.update(order='CTX-DELETED1', persisted_order='CTX-DELETED1')
        self.assertIn('foreign, neighboring or deleted session memory observed',
                      benchmark.assess(case, 2048, row)['failures'])

    def test_configured_budget_blocks_forced_model_execution_on_oversize(self):
        case, row = self.example('mandatory-input-over-budget', 2048)
        self.assertEqual([], benchmark.assess(case, 2048, row)['failures'])
        case, invoked = self.example(case['id'], 4096, content='ignored limit')
        self.assertIn('oversized required context invoked model',
                      benchmark.assess(case, 4096, invoked)['failures'])
        self.assertIn('unexpected model failure or skip', benchmark.assess(case, 0, row)['failures'])

    def test_corrected_order_must_be_restored_and_protected_from_model_override(self):
        case, row = self.example('corrected-order-followup', 4096, content='正确历史')
        row.update(order='CTX-OLD001', persisted_order='CTX-OLD001')
        row['context']['remembered_slots'] = {'order_no': 'CTX-OLD001'}
        failures = benchmark.assess(case, 4096, row)['failures']
        self.assertIn('recognized output overrode restored order', failures)
        self.assertIn('known order missing from bounded model context', failures)

    def test_stub_intents_cannot_be_reported_as_model_task_success(self):
        case, row = self.example('same-session-across-tenants', 4096, content='CTX-LOCAL1')
        row['score'] = benchmark.assess(case, 4096, row)
        self.assertTrue(row['score']['intent_match'] and row['score']['order_match'])
        self.assertIsNone(benchmark.totals([row], live=False)['intent_and_order_correct'])
