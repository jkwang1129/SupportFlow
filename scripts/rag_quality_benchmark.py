"""Compare RAG profiles on the frozen synthetic corpus; live generation is opt-in."""
import argparse
import asyncio
from collections import Counter
from datetime import datetime, timezone
from hashlib import sha256
from importlib.metadata import version
import json
import logging
import math
from pathlib import Path
import platform
import re
import secrets
import subprocess
import sys
from time import perf_counter
from types import SimpleNamespace
from unittest.mock import patch

import psycopg
from langsmith import tracing_context
from psycopg import sql
from psycopg.rows import dict_row

from isolated_postgres import disposable_postgres
from migration_engine import load_plan, run_plan
from rag_quality_dataset import COMPARISONS, SUITES, load_suite

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'ai-service'))

from ai.rag import embedding, graph, grounded_reply, llama_engine, pipeline, retriever, store
from ai.services import rag
from rag_quality_provider import ModelCallFailure, QwenGeneration

TOP_K = 5
PROFILES = {'single_pass': 0, 'agentic': graph.MAX_REWRITE_ROUNDS, 'grounded': graph.MAX_REWRITE_ROUNDS}
VECTOR_THRESHOLD = graph.VECTOR_MIN_SCORE
KEYWORD_THRESHOLD = graph.KEYWORD_MIN_SCORE


def document_status(document, as_of):
    current = document['valid_from'] <= as_of and (document['valid_until'] is None or as_of < document['valid_until'])
    if document['publication'] == 'draft':
        return 1
    if document['publication'] == 'published' and current:
        return 3
    return 4


def seed_documents(connection, dataset, db_ids):
    for document in dataset['documents']:
        doc_id = db_ids[document['id']]
        status = document_status(document, dataset['as_of'])
        index_status = 3 if document['index_status'] == 'ready' else 4
        connection.execute('''INSERT INTO kb_document (id,tenant_code,doc_no,title,status,index_status)
            VALUES (%s,%s,%s,%s,%s,%s)''', (doc_id, document['tenant'], document['id'], document['title'], status, index_status))
        # Each small synthetic document is one fixture chunk. The local keyword path does not read its vector.
        connection.execute('''INSERT INTO kb_chunk (id,tenant_code,doc_id,chunk_no,content,embedding_model)
            VALUES (%s,%s,%s,0,%s,'local-hash')''', (doc_id, document['tenant'], doc_id, document['text']))


def rag_input(case, db_ids):
    scope = case['document_scope']
    question = case['question']
    if case['history']:
        history = '\n'.join(f"{turn['role']}: {turn['content']}" for turn in case['history'])
        question = f'历史回合\n{history}\n当前问题\n{question}'
    return question, {
        'tenant_code': case['tenant'],
        'top_k': TOP_K,
        'doc_ids': None if scope is None else [db_ids[doc] for doc in scope],
        'published_only': True,
    }


def fixture_reply(payload, *, grounded=False):
    # Only read the first evidence block in the actual prompt. No test labels or per-question routing.
    prompt = payload['messages'][-1]['content']
    evidence = (json.loads(prompt)['sources'][0]['content'] if grounded
                else prompt.split('\n\n【问题】\n', 1)[0].splitlines()[2])
    sentence = re.split(r'(?<=[。！？])', evidence, maxsplit=1)[0]
    content = f'{sentence} [1]'
    if grounded:
        content = json.dumps({'decision': 'answer', 'claims': [{'text': sentence, 'sources': [{'index': 1, 'quote': sentence}]}]},
                             ensure_ascii=False)
    return {'choices': [{'message': {'content': content}}]}


def allowed_documents(case, documents, as_of):
    scope = case['document_scope']
    return {
        doc['id'] for doc in documents
        if doc['tenant'] == case['tenant'] and (scope is None or doc['id'] in scope)
        and document_status(doc, as_of) == 3 and doc['index_status'] == 'ready'
    }


def summarize_hit(hit, logical_ids):
    return {'document_id': logical_ids.get(hit['doc_id']), 'chunk_id': str(hit['id']),
            'chunk_no': hit['chunk_no'], 'score': hit['score'], 'content': hit['content']}


async def observe(question, arguments, profile, logical_ids, live=None):
    rounds = []
    model_calls = 0
    receipt = None
    retrieve_real = retriever.retrieve_with_meta

    def record_retrieval(**request):
        result = retrieve_real(**request)
        rounds.append({'query': request['query'], 'mode': result['mode'],
                       'hits': [summarize_hit(hit, logical_ids) for hit in result['results']]})
        return result

    async def record_generation(_provider, payload, _timeout, **_metadata):
        nonlocal model_calls, receipt
        model_calls += 1
        if live is not None:
            receipt = {'attempted': False}
            return await live.generate(payload, receipt)
        return fixture_reply(payload, grounded=profile == 'grounded')

    start = perf_counter()
    error = None
    result = {}
    graph_settings = SimpleNamespace(rag_vector_min_score=VECTOR_THRESHOLD, rag_keyword_min_score=KEYWORD_THRESHOLD,
                                     rag_require_extractive_support=False, rag_require_grounded_reply=profile == 'grounded')
    with (patch.object(retriever, 'retrieve_with_meta', record_retrieval),
          patch.object(graph, 'get_settings', return_value=graph_settings),
          patch.object(rag, 'call_chat', record_generation)):
        try:
            result = await graph.ask(question, **arguments, max_rewrite_rounds=PROFILES[profile])
        except ModelCallFailure as failure:
            error = failure.code
        except Exception as failure:
            error = type(failure).__name__
    elapsed_ms = (perf_counter() - start) * 1000
    if any(round_['mode'] == 'error' for round_ in rounds):
        error = error or 'retrieval_unavailable'
    if result.get('abstention_reason') == 'stale_evidence':
        error = error or 'stale_evidence'
    return {
        'profile': profile, 'latency_ms': round(elapsed_ms, 3), 'error': error, 'engine': result.get('engine'),
        'decision': result.get('decision'), 'abstention_reason': result.get('abstention_reason'),
        'answer': result.get('answer'), 'steps': result.get('steps', []), 'retrievals': rounds,
        'retrieved': [summarize_hit(hit, logical_ids) for hit in result.get('retrieved', [])],
        'citations': [
            item | {'document_id': logical_ids.get(int(item['doc_id']))} for item in result.get('citations', [])
        ],
        'model_calls': model_calls, 'generation_receipt': receipt,
        'provider_tokens': receipt.get('usage') if receipt else None,
    }


def score(case, allowed, observation):
    expected = case['expected']
    relevant = set(expected['relevant_document_ids'])
    hits = observation['retrievals'][-1]['hits'] if observation['retrievals'] else []
    retrieved_top_k = {hit['document_id'] for hit in hits[:TOP_K]}
    recall = len(relevant & retrieved_top_k) / len(relevant) if relevant else None
    if observation['error'] and relevant:
        recall = 0.0
    all_hits = [hit for attempt in observation['retrievals'] for hit in attempt['hits']]
    unauthorized = sorted({str(hit['document_id']) for hit in all_hits + observation['retrieved']
                           if hit['document_id'] not in allowed})
    answer = observation['answer'] or ''
    referenced = {int(index) for index in re.findall(r'\[([0-9]{1,6})\]', answer)}
    citations = observation['citations']
    citation_pairs = []
    for item in citations:
        index = item['index']
        matching_hit = observation['retrieved'][index - 1] if 1 <= index <= len(observation['retrieved']) else None
        matched = (matching_hit is not None and index in referenced and item['document_id'] in allowed
                   and all(item[key] == matching_hit[key] for key in ('document_id', 'chunk_id', 'chunk_no', 'content')))
        citation_pairs.append({'index': index, 'valid': matched})
    expected_source = observation['decision'] == 'answer'
    citation_violation = (referenced != {item['index'] for item in citations}
                          or expected_source != bool(citations) or len(citations) != len(referenced)
                          or any(not item['valid'] for item in citation_pairs))
    return {
        'recall_at_5': recall, 'citation_pairs': citation_pairs, 'citation_violation': citation_violation,
        'unauthorized_document_ids': unauthorized,
        'forbidden_output_markers': [marker for marker in expected['forbidden_markers'] if marker in answer],
        'outcome_match': (observation['decision'] == expected['outcome'] and not observation['error']
                          if expected['outcome'] != 'handoff' else None),
    }


def percentile(values, fraction):
    return sorted(values)[math.ceil(len(values) * fraction) - 1]


def aggregate(rows, *, live=False):
    recalls = [row['scores']['recall_at_5'] for row in rows if row['scores']['recall_at_5'] is not None]
    citations = [item['valid'] for row in rows for item in row['scores']['citation_pairs']]
    decisions = Counter(row['decision'] or 'no_decision' for row in rows)
    latencies = [row['latency_ms'] for row in rows]
    expected_refusals = [row for row in rows if row['expected_outcome'] == 'abstain']
    expected_answers = [row for row in rows if row['expected_outcome'] == 'answer']
    false_refusals = sum(row['decision'] == 'abstain' and not row['error'] for row in expected_answers)
    provider_tokens = None
    if live:
        attempted = [row for row in rows if (row['generation_receipt'] or {}).get('attempted')]
        receipts = [row['provider_tokens'] for row in attempted if row['provider_tokens'] is not None]
        provider_tokens = {'attempted': len(attempted), 'with_usage': len(receipts),
                           'unknown': len(attempted) - len(receipts),
                           'observed': {name: sum(usage[name] for usage in receipts)
                                        for name in ('prompt_tokens', 'completion_tokens', 'total_tokens')} if receipts else None}
    return {
        'observations': len(rows), 'decisions': dict(decisions), 'errors': sum(bool(row['error']) for row in rows),
        'recall_at_5': {'sum': round(sum(recalls), 6), 'eligible': len(recalls),
                        'mean': round(sum(recalls) / len(recalls), 6) if recalls else None},
        'citation_reference_validity': {'valid': sum(citations), 'total': len(citations)},
        'outcome_matches': sum(row['scores']['outcome_match'] is True for row in rows),
        'outcome_eligible': sum(row['scores']['outcome_match'] is not None for row in rows),
        'abstention_rate': round(decisions['abstain'] / len(rows), 6),
        'abstention_reasons': dict(Counter(row['abstention_reason'] for row in rows if row.get('abstention_reason'))),
        'expected_abstention_hits': {'matched': sum(row['scores']['outcome_match'] is True for row in expected_refusals),
                                     'total': len(expected_refusals)},
        'unexpected_abstentions': {'observed': false_refusals, 'total': len(expected_answers)},
        'unauthorized_retrieval_cases': sum(bool(row['scores']['unauthorized_document_ids']) for row in rows),
        'forbidden_output_marker_cases': sum(bool(row['scores']['forbidden_output_markers']) for row in rows),
        'latency_ms': {'p50': percentile(latencies, 0.50), 'p95': percentile(latencies, 0.95)},
        'retrieval_calls': sum(len(row['retrievals']) for row in rows),
        'model_calls': sum(row['model_calls'] for row in rows), 'provider_tokens': provider_tokens,
        'answer_correctness': None, 'citation_semantic_support': None, 'handoff_accuracy': None,
    }


def violations(case, observations):
    failures = []
    for row in observations:
        label = f"{case['id']}/{row['profile']}"
        if row['error'] or row['engine'] != 'langgraph' or row['decision'] not in {'answer', 'abstain'}:
            failures.append(f'{label}: RAG execution unavailable')
        if row['scores']['unauthorized_document_ids']:
            failures.append(f'{label}: unauthorized retrieval')
        if row['scores']['citation_violation']:
            failures.append(f'{label}: broken citation contract')
        count = len(row['retrievals'])
        if ((case['document_scope'] == [] and (count or row['decision'] != 'abstain' or row['model_calls']))
                or (case['document_scope'] != [] and not 1 <= count <= 1 + PROFILES[row['profile']])
                or row['model_calls'] > 1):
            failures.append(f'{label}: execution budget or empty scope violation')
    first, second = observations
    if first['retrievals'][:1] != second['retrievals'][:1]:
        failures.append(f"{case['id']}: initial retrieval differs between profiles")
    elif PROFILES[first['profile']] == PROFILES[second['profile']] and first['retrievals'] != second['retrievals']:
        failures.append(f"{case['id']}: later retrieval differs despite equal rewrite budgets")
    return failures


async def run_cases(dataset, db_ids, live=None, *, comparison='rewrite'):
    rows = []
    failures = []
    logical_ids = {value: key for key, value in db_ids.items()}
    for offset, case in enumerate(dataset['cases']):
        question, arguments = rag_input(case, db_ids)
        allowed = allowed_documents(case, dataset['documents'], dataset['as_of'])
        pair = COMPARISONS[comparison]
        order = list(pair if offset % 2 == 0 else reversed(pair))
        observations = []
        for profile in order:
            row = await observe(question, arguments, profile, logical_ids, live)
            row['expected_outcome'] = case['expected']['outcome']
            row['scores'] = score(case, allowed, row)
            observations.append(row)
        failures.extend(violations(case, observations))
        rows.append({'id': case['id'], 'category': case['category'], 'tenant': case['tenant'],
                     'document_scope': case['document_scope'], 'execution_order': order, 'observations': observations})
    return rows, failures


def benchmark(dataset, fixture, live=None, *, comparison='rewrite'):
    if not graph.langgraph_available():
        raise RuntimeError('Install the AI test requirements to run both LangGraph profiles')
    db_ids = {document['id']: 28_000_001 + offset for offset, document in enumerate(dataset['documents'])}
    password = secrets.token_hex(24)
    started = datetime.now(timezone.utc).isoformat()
    with disposable_postgres() as (connect, environment):
        with connect('customer_db') as connection:
            run_plan(connection, load_plan()['customer_db'])
            with connection.transaction():
                seed_documents(connection, dataset, db_ids)
                connection.execute(sql.SQL('CREATE ROLE rag_quality_reader LOGIN PASSWORD {}').format(sql.Literal(password)))
                connection.execute('GRANT supportflow_kb_tenant TO rag_quality_reader')
            postgres = connection.execute('SHOW server_version').fetchone()[0]

        def connect_reader():
            return psycopg.connect(host='127.0.0.1', port=environment['SUPPORTFLOW_DB_PORT'], dbname='customer_db',
                                   user='rag_quality_reader', password=password, row_factory=dict_row, connect_timeout=2)

        generation = (live.chosen, 'qwen live') if live is not None else ({'model': 'fixture-first-sentence-v1'}, 'local fixture')
        with (tracing_context(enabled=False),
              patch.object(store, 'connect', connect_reader),
              patch.object(llama_engine, 'available', return_value=False),
              patch.object(embedding, 'get_settings', return_value=SimpleNamespace(embedding_key=lambda: None)),
              patch.object(graph, '_graph', graph._build_graph()),
              patch.object(rag, 'get_settings', return_value=SimpleNamespace(llm_timeout=5)),
              patch.object(rag, 'resolve_provider', return_value=generation)):
            rows, failures = asyncio.run(run_cases(dataset, db_ids, live, comparison=comparison))

    return {
        'schema_version': 2, 'fixture': fixture, 'violations': failures, 'cases': rows,
        'configuration': {
            'comparison': comparison, 'profile_rewrite_limits': {name: PROFILES[name] for name in COMPARISONS[comparison]},
            'top_k': TOP_K, 'published_only': True,
            'embedding': 'local-hash', 'retrieval': 'postgres-keyword',
            'generation': 'qwen-live' if live is not None else 'fixture-first-sentence-v1',
            'live_profile': live.configuration() if live is not None else None,
            'keyword_threshold': KEYWORD_THRESHOLD, 'vector_threshold': VECTOR_THRESHOLD, 'extractive_only': False,
            'history': 'shared prompt prefix', 'timing': 'sequential graph calls, alternating profile order, no warmup',
        },
        'environment': {
            'platform': platform.platform(), 'python': platform.python_version(), 'postgres': postgres,
            'started_at_utc': started,
            'git_head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
            'langgraph': version('langgraph'), 'psycopg': version('psycopg'),
            'source_sha256': {
                path.relative_to(ROOT).as_posix(): sha256(path.read_bytes().replace(b'\r\n', b'\n')).hexdigest()
                for path in (Path(__file__), Path(__file__).with_name('rag_quality_provider.py'),
                             *(Path(module.__file__) for module in (graph, grounded_reply, pipeline, store, embedding, rag)))
            },
        },
        'metrics': {profile: aggregate([row for case in rows for row in case['observations'] if row['profile'] == profile], live=live is not None)
                    for profile in COMPARISONS[comparison]},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='Path for the JSON report with paired observations')
    parser.add_argument('--live-qwen', action='store_true', help='Send the fixed synthetic prompts to paid Qwen Chat')
    parser.add_argument('--comparison', choices=COMPARISONS, default='rewrite', help='Choose the paired profiles')
    parser.add_argument('--suite', choices=SUITES, default='baseline', help='Choose the frozen dataset')
    args = parser.parse_args()
    logging.basicConfig(level=logging.ERROR)
    dataset, fixture = load_suite(args.suite)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.live_qwen:
        try:
            live = QwenGeneration.from_local_config(ROOT / 'ai-service/.env')
            # Reserve a new path before any paid call. Existing raw evidence must survive a repeat.
            output = args.output.open('x', encoding='utf-8')
        except (ValueError, OSError) as error:
            parser.error(str(error))
    else:
        live = None
        output = args.output.open('w', encoding='utf-8')
    with output:
        report = benchmark(dataset, fixture, live, comparison=args.comparison)
        output.write(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'metrics': report['metrics'], 'violations': report['violations']}, sort_keys=True))
    return 1 if report['violations'] else 0


if __name__ == '__main__':
    sys.exit(main())
