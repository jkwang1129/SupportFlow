"""Check benchmark bookkeeping separately from the disposable database run."""
import asyncio
from copy import deepcopy
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import rag_quality_benchmark as benchmark


class BenchmarkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dataset, _ = benchmark.load_fixture()
        cls.case = next(case for case in cls.dataset['cases'] if case['id'] == 'relevant-but-insufficient')
        cls.allowed = benchmark.allowed_documents(cls.case, cls.dataset['documents'], cls.dataset['as_of'])

    def observation(self, profile='single_pass'):
        hit = {'document_id': 'shipping-standard', 'chunk_id': '3', 'chunk_no': 0, 'content': '原文', 'score': 0.4}
        return {
            'profile': profile, 'error': None, 'latency_ms': 1.0, 'decision': 'answer', 'engine': 'langgraph',
            'expected_outcome': 'abstain',
            'answer': '原文 [1]', 'retrievals': [{'query': '原始问句', 'mode': 'keyword', 'hits': [hit]}],
            'retrieved': [hit], 'citations': [hit | {'index': 1}], 'model_calls': 1,
        }

    def test_input_is_independent_of_gold_and_preserves_explicit_scope(self):
        case = dict(self.case, document_scope=[], history=[{'role': 'user', 'content': '上一轮'}])
        query, args = benchmark.rag_input(case, {})
        self.assertEqual((query, args), benchmark.rag_input(dict(case, expected=None), {}))
        self.assertIn('上一轮', query)
        self.assertTrue(args['published_only'])
        self.assertEqual([], args['doc_ids'])
        self.assertIsNone(benchmark.rag_input(dict(case, document_scope=None), {})[1]['doc_ids'])

    def test_partial_evidence_counts_toward_recall_but_does_not_validate_answer(self):
        row = self.observation()
        row['scores'] = benchmark.score(self.case, self.allowed, row)
        self.assertEqual(1.0, row['scores']['recall_at_5'])
        self.assertFalse(row['scores']['outcome_match'])
        self.assertFalse(row['scores']['citation_violation'])
        summary = benchmark.aggregate([row])
        self.assertEqual({'valid': 1, 'total': 1}, summary['citation_reference_validity'])
        self.assertEqual({'matched': 0, 'total': 1}, summary['expected_abstention_hits'])
        for metric in ('answer_correctness', 'citation_semantic_support', 'handoff_accuracy', 'provider_tokens'):
            self.assertIsNone(summary[metric])

    def test_wrong_source_or_missing_citation_fails_even_with_valid_looking_reference(self):
        for replacement in ([], [self.observation()['citations'][0] | {'chunk_id': '99'}]):
            with self.subTest(replacement=replacement):
                row = dict(self.observation(), citations=replacement)
                self.assertTrue(benchmark.score(self.case, self.allowed, row)['citation_violation'])

    def test_earlier_unauthorized_retrieval_is_detected_after_rewrite_replaces_it(self):
        row = self.observation('agentic')
        extra = deepcopy(row['retrievals'][0])
        extra['hits'][0]['document_id'] = 'warranty-eval-b'
        row['retrievals'].insert(0, extra)
        scores = benchmark.score(self.case, self.allowed, row)
        self.assertEqual(['warranty-eval-b'], scores['unauthorized_document_ids'])
        self.assertEqual(1.0, scores['recall_at_5'])

    def test_handoff_is_not_inferred_from_abstention_text(self):
        handoff = dict(self.case, expected=self.case['expected'] | {'outcome': 'handoff'})
        row = dict(self.observation(), decision='abstain', answer='请联系人工客服。', citations=[])
        self.assertIsNone(benchmark.score(handoff, self.allowed, row)['outcome_match'])

    def test_retrieval_errors_remain_in_report_and_fail_the_gate(self):
        async def failed_ask(_question, **_arguments):
            benchmark.retriever.retrieve_with_meta(query='原始问句')
            return {'engine': 'langgraph', 'decision': 'abstain', 'abstention_reason': 'retrieval_unavailable'}

        with (patch.object(benchmark.retriever, 'retrieve_with_meta', return_value={'mode': 'error', 'results': []}),
              patch.object(benchmark.graph, 'ask', failed_ask)):
            row = asyncio.run(benchmark.observe('原始问句', {}, 'single_pass', {}))
        self.assertEqual('retrieval_unavailable', row['error'])
        row['scores'] = benchmark.score(self.case, self.allowed, row)
        other = row | {'profile': 'agentic'}
        self.assertEqual(0.0, row['scores']['recall_at_5'])
        self.assertFalse(row['scores']['outcome_match'])
        self.assertEqual(2, len(benchmark.violations(self.case, [row, other])))

    def test_changed_initial_query_and_budget_are_reported(self):
        single, agentic = self.observation(), self.observation('agentic')
        single['retrievals'].append(deepcopy(single['retrievals'][0]))
        single['retrievals'][0]['query'] = '不相同的输入'
        for row in (single, agentic):
            row['scores'] = benchmark.score(self.case, self.allowed, row)
        self.assertEqual([
            f"{self.case['id']}/single_pass: execution budget or empty scope violation",
            f"{self.case['id']}: initial retrieval differs between profiles",
        ], benchmark.violations(self.case, [single, agentic]))
