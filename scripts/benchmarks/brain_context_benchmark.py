"""Compare existing brain context policies on a frozen synthetic intent/memory workload."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
import argparse
import asyncio
from datetime import datetime, timezone
from hashlib import sha256
import json
import logging
from pathlib import Path
import platform
import secrets
import subprocess
import sys
from time import perf_counter
from types import SimpleNamespace
from unittest.mock import patch

import psycopg
from psycopg import sql
from psycopg.rows import dict_row

from isolated_postgres import disposable_postgres
from migration_engine import load_plan, run_plan
from rag_quality_dataset import canonical_digest

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / 'fixtures/evaluation/brain-context-v1.json'
sys.path.insert(0, str(ROOT / 'ai-service'))

from ai.agents import brain as agent
from ai.services import brain
from rag_quality_provider import ModelCallFailure, QwenGeneration

PROFILES = {'legacy': 0, 'bounded_2048': 2048, 'bounded_4096': 4096}
TENANT = 'CTX_A'
FOREIGN = 'CTX_B'
STUB = {'provider': 'fixture', 'model': 'ctx-observer-v1'}


def load_fixture(path=FIXTURE):
    dataset = json.loads(path.read_bytes())
    if canonical_digest(dataset) != path.with_suffix('.sha256').read_text(encoding='ascii').strip():
        raise ValueError('context fixture differs from the frozen version')
    return dataset


def fixture_input(case, noise):
    history = [dict(role=message['role'], content=(message.get('content') or noise * message['noise_repeats']))
               for message in case['history']]
    return case['question'] + noise * case.get('question_noise_repeats', 0), history


def excluded_orders(case):
    values = [case.get('foreign_order'), case.get('neighbor_order')]
    if case.get('delete_memory'):
        values.append(case['memory']['order_no'])
    return {value for value in values if value}


def capture_context(payload, question, limit):
    messages = payload['messages']
    content = messages[-1]['content']
    envelope = json.loads(content) if limit else None
    return {
        'context_bytes': len(json.dumps(messages, ensure_ascii=False, separators=(',', ':')).encode()),
        'question_preserved': envelope['question'] == question if envelope else question in content,
        'retained_history': envelope['history'] if envelope else None,
        'remembered_slots': envelope['remembered_slots'] if envelope else None,
        'payload': payload,
    }


def assess(case, limit, row):
    expected = case['expected']
    context = row['context']
    model_content = context['payload']['messages'][-1]['content'] if context else ''
    missing_anchors = [anchor for anchor in expected['anchors'] if anchor not in model_content]
    expected_skip = bool(limit and expected.get('bounded_fallback'))
    failures = []
    if row['restored_order'] != expected['restored_order']:
        failures.append('memory restored incorrectly')
    if row['restored_order'] and row['restored_order'] != row['order']:
        failures.append('recognized output overrode restored order')
    if row['persisted_order'] != row['order'] or not row['turn_persisted']:
        failures.append('recognized state did not persist')
    if any(value in model_content or value in (row['order'], row['persisted_order']) for value in excluded_orders(case)):
        failures.append('foreign, neighboring or deleted session memory observed')
    if row['error'] or (not context and not expected_skip) or (context and not row['model_parse_ok']):
        failures.append('unexpected model failure or skip')
    if expected_skip and context is not None:
        failures.append('oversized required context invoked model')
    if context and (not context['question_preserved'] or (limit and context['context_bytes'] > limit)):
        failures.append('question or context budget violated')
    if limit and context and row['restored_order'] and context['remembered_slots'].get('order_no') != row['restored_order']:
        failures.append('known order missing from bounded model context')
    if missing_anchors and not (limit == PROFILES['bounded_2048'] and expected.get('allow_anchor_loss_at_2048')):
        failures.append('required historical evidence lost')
    return {
        'missing_anchors': missing_anchors, 'anchor_count': len(expected['anchors']), 'failures': failures,
        'intent_match': row['intent'] == expected['intent'], 'order_match': row['order'] == expected['order'],
    }


def totals(rows, *, live):
    scores = [row['score'] for row in rows]
    sent = [row for row in rows if row['receipt'].get('attempted')]
    measured = [row['receipt']['usage'] for row in sent if row['receipt'].get('usage') is not None]
    samples = sorted(row['latency_ms'] for row in rows)
    return {
        'cases': len(rows), 'intent_correct': sum(item['intent_match'] for item in scores) if live else None,
        'intent_and_order_correct': sum(item['intent_match'] and item['order_match'] for item in scores) if live else None,
        'order_correct': sum(item['order_match'] for item in scores),
        'anchors_expected': sum(item['anchor_count'] for item in scores),
        'anchors_retained': sum(item['anchor_count'] - len(item['missing_anchors']) for item in scores),
        'model_prompt_count': sum(row['context'] is not None for row in rows),
        'context_bytes': sum(row['context']['context_bytes'] for row in rows if row['context']),
        'provider_calls': len(sent), 'usage_receipts': len(measured),
        'tokens': {name: sum(usage[name] for usage in measured)
                   for name in ('prompt_tokens', 'completion_tokens', 'total_tokens')} if measured else None,
        'latency_ms': {'p50': samples[(len(samples) - 1) // 2], 'p95': samples[(95 * len(samples) + 99) // 100 - 1]},
    }


async def sample(case, noise, profile, connect_admin, live):
    question, history = fixture_input(case, noise)
    case_key = sha256(case['id'].encode()).hexdigest()[:12]
    session = f'ctx-{profile}-{case_key}'
    state = {'tenant_code': TENANT, 'session_no': session, 'question': question, 'history': history}
    if case.get('memory'):
        brain.save_dialogue(TENANT, session, {'slots': case['memory'], 'turn_count': 1})
    if case.get('foreign_order'):
        brain.save_dialogue(FOREIGN, session, {'slots': {'order_no': case['foreign_order']}, 'turn_count': 1})
    if case.get('neighbor_order'):
        brain.save_dialogue(TENANT, session + '-other',
                            {'slots': {'order_no': case['neighbor_order']}, 'turn_count': 1})
    if case.get('delete_memory'):
        with connect_admin('ai_db') as conn:
            changed = conn.execute('UPDATE bot_dialogue SET is_deleted=TRUE WHERE tenant_code=%s AND session_no=%s',
                                   (TENANT, session)).rowcount
            if changed != 1:
                raise RuntimeError('failed to seed deleted memory case')

    limit = PROFILES[profile]
    settings = SimpleNamespace(brain_context_max_bytes=limit, llm_timeout=25)
    with patch.object(brain, 'resolve_provider', return_value=(None, 'disabled for prior turn')):
        if case.get('prior_question'):
            prior = state | {'question': case['prior_question'], 'history': []}
            agent.node_persist(prior | await agent.node_recognize(prior))

    observed = {'context': None, 'error': None, 'receipt': {}}

    async def generate(_chosen, payload, _timeout, **_metadata):
        if observed['context'] is not None:
            observed['error'] = 'duplicate_model_call'
            raise RuntimeError('intent analysis can call the model once')
        observed['context'] = capture_context(payload, question, limit)
        if any(value in payload['messages'][-1]['content'] for value in excluded_orders(case)):
            observed['error'] = 'excluded_memory_in_prompt'
            raise RuntimeError('refusing to submit excluded memory')
        if live is None:
            return {'choices': [{'message': {'content': '{"intent":"其他","slots":{}}'}}]}
        try:
            return await live.generate(payload, observed['receipt'], scene='意图上下文评测')
        except ModelCallFailure as failure:
            observed['error'] = failure.code
            raise

    chosen = live.chosen if live is not None else STUB
    with (patch.object(brain, 'get_settings', return_value=settings),
          patch.object(brain, 'resolve_provider', return_value=(chosen, 'context benchmark')),
          patch.object(brain, 'call_chat', generate)):
        start = perf_counter()
        result = await agent.node_recognize(state)
        elapsed = round((perf_counter() - start) * 1000, 3)
    agent.node_persist(state | result)
    persisted = brain.load_dialogue(TENANT, session)
    observed.update(profile=profile, budget_bytes=limit, latency_ms=elapsed,
                    model_parse_ok=result['model_result'] is not None,
                    intent=(result['intent'] or {}).get('name', '其他'), order=result['slots'].get('order_no'),
                    restored_order=result['dialogue']['slots'].get('order_no'), persisted_order=persisted['slots'].get('order_no'),
                    turn_persisted=persisted['turn_count'] == result['turn_count'])
    observed['score'] = assess(case, limit, observed)
    return observed


async def run_cases(dataset, connect_admin, live):
    results = []
    profiles = list(PROFILES)
    for offset, case in enumerate(dataset['cases']):
        start = offset % len(profiles)
        order = profiles[start:] + profiles[:start]
        arms = [await sample(case, dataset['noise'], profile, connect_admin, live) for profile in order]
        results.append({'case_id': case['id'], 'expected': case['expected'], 'profiles': arms})
    return results


def benchmark(dataset, live):
    started = datetime.now(timezone.utc).isoformat()
    password = secrets.token_hex(24)
    with disposable_postgres() as (connect_admin, environment):
        with connect_admin('ai_db') as conn:
            run_plan(conn, load_plan()['ai_db'])
            postgres = conn.execute('SHOW server_version').fetchone()[0]
            with conn.transaction():
                conn.execute(sql.SQL('CREATE ROLE brain_eval LOGIN PASSWORD {}').format(sql.Literal(password)))
                conn.execute('GRANT USAGE ON SCHEMA public TO brain_eval')
                conn.execute('GRANT SELECT ON bot_setting TO brain_eval')
                conn.execute('GRANT SELECT, UPDATE ON bot_intent TO brain_eval')
                conn.execute('GRANT SELECT, INSERT, UPDATE ON bot_dialogue TO brain_eval')
                for index, name in enumerate(dataset['intents'], 1):
                    conn.execute('INSERT INTO bot_intent(id,tenant_code,intent_code,name) VALUES (%s,%s,%s,%s)',
                                 (33_000_000 + index, TENANT, f'CTX_INTENT_{index}', name))

        def connect_reader():
            return psycopg.connect(host='127.0.0.1', port=environment['SUPPORTFLOW_DB_PORT'], dbname='ai_db',
                                   user='brain_eval', password=password, row_factory=dict_row, connect_timeout=2)

        with (patch.object(brain, 'connect', connect_reader),
              patch.object(brain, 'match_intent_by_version', return_value=None)):
            results = asyncio.run(run_cases(dataset, connect_admin, live))

    failures = [f"{case['case_id']}/{row['profile']}: {problem}" for case in results for row in case['profiles']
                for problem in row['score']['failures']]
    return {
        'schema_version': 1, 'dataset': dataset['version'], 'dataset_sha256': canonical_digest(dataset),
        'mode': 'qwen-live' if live is not None else 'deterministic-stub', 'profiles': PROFILES,
        'environment': {'platform': platform.platform(), 'python': platform.python_version(), 'postgres': postgres,
                        'started_at_utc': started, 'git_head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                        'source_sha256': {path.relative_to(ROOT).as_posix(): sha256(path.read_bytes().replace(b'\r\n', b'\n')).hexdigest()
                                          for path in (Path(__file__), Path(brain.__file__), Path(agent.__file__),
                                                       Path(__file__).with_name('rag_quality_provider.py'))}},
        'provider': live.configuration() | {'temperature': 0.1} if live is not None else None,
        'cases': results, 'violations': failures,
        'metrics': {profile: totals([row for case in results for row in case['profiles'] if row['profile'] == profile],
                                    live=live is not None) for profile in PROFILES},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--live-qwen', action='store_true',
                        help='Send only the built-in synthetic analysis prompts to Qwen')
    args = parser.parse_args()
    logging.basicConfig(level=logging.ERROR)
    try:
        dataset = load_fixture()
        live = QwenGeneration.from_local_config(ROOT / 'ai-service/.env') if args.live_qwen else None
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open('x' if args.live_qwen else 'w', encoding='utf-8') as stream:
            report = benchmark(dataset, live)
            stream.write(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    except (ValueError, OSError) as error:
        parser.error(str(error))
    print(json.dumps({'metrics': report['metrics'], 'violations': report['violations']}, sort_keys=True))
    return 1 if report['violations'] else 0


if __name__ == '__main__':
    sys.exit(main())
