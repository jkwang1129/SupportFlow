"""Compare keyword, dense, and reciprocal rank fusion on two frozen synthetic suites."""
import argparse
import asyncio
from datetime import datetime, timezone
from hashlib import sha256
import json
from math import log2
from pathlib import Path
import platform
import secrets
import subprocess
from time import perf_counter
from unittest.mock import patch

import psycopg
from psycopg import sql
from psycopg.rows import dict_row

from isolated_postgres import disposable_postgres
from migration_engine import load_plan, run_plan
from rag_quality_benchmark import ROOT, TOP_K, allowed_documents, embedding, percentile, rag_input, seed_documents, store
from rag_quality_dataset import canonical_digest, load_suite
from rag_quality_provider import (BASE_URL, EMBEDDING_MODEL, MAX_EMBEDDING_CALLS, MAX_EMBEDDING_REQUEST_BYTES,
                                  TIMEOUT_SECONDS, ModelCallFailure, encode_qwen_groups, qwen_api_key)


SUITES = ('baseline', 'holdout')
PROFILES = ('keyword', 'dense', 'rrf')
LEG_DEPTH = 10
RRF_CONSTANT = 60


def reciprocal_rank_fusion(keyword, dense):
    """Fuse order only, counting each candidate at most once per leg."""
    by_id = {}
    for results in (keyword, dense):
        seen = set()
        for rank, hit in enumerate(results, start=1):
            chunk_id = hit['id']
            if chunk_id in seen:
                continue
            seen.add(chunk_id)
            item, ranks = by_id.setdefault(chunk_id, (hit, []))
            ranks.append(rank)
    scores = [(sum(1 / (RRF_CONSTANT + rank) for rank in ranks), min(ranks), item)
              for item, ranks in by_id.values()]
    ordered = sorted(scores, key=lambda value: (-value[0], value[1], value[2]['id']))
    return [hit | {'score': round(score, 10)} for score, _, hit in ordered[:TOP_K]]


def ranking_metrics(document_ids, relevant):
    if not relevant:
        return None
    first_rank = None
    gained = set()
    dcg = 0.0
    for rank, document_id in enumerate(document_ids[:TOP_K], start=1):
        if document_id in relevant and document_id not in gained:
            first_rank = first_rank or rank
            gained.add(document_id)
            dcg += 1 / log2(rank + 1)
    ideal = sum(1 / log2(rank + 1) for rank in range(1, min(len(relevant), TOP_K) + 1))
    return {'recall_at_5': len(gained) / len(relevant), 'mrr_at_5': 1 / first_rank if first_rank else 0.0,
            'ndcg_at_5': dcg / ideal}


def check_scope(ranking, allowed):
    return [hit['document_id'] for hit in ranking if hit['document_id'] not in allowed]


def serialize_hits(hits, db_names):
    return [{'chunk_id': str(hit['id']), 'document_id': db_names.get(hit['doc_id']), 'score': hit['score']}
            for hit in hits]


def measure_case(case, query, scope, vector, db_names, allowed, *, dense_first):
    keyword = lambda: store.keyword_search(query=query, **scope)
    dense = lambda: store.search(vector=vector, **scope)
    methods = (('dense', dense), ('keyword', keyword)) if dense_first else (('keyword', keyword), ('dense', dense))
    legs, durations = {}, {}
    for profile, search in methods:
        started = perf_counter()
        legs[profile] = search()
        durations[profile] = (perf_counter() - started) * 1000
    started = perf_counter()
    fused = reciprocal_rank_fusion(legs['keyword'], legs['dense'])
    durations['rrf'] = sum(durations.values()) + (perf_counter() - started) * 1000
    full = {name: serialize_hits(hits, db_names) for name, hits in legs.items()}
    relevant = set(case['expected']['relevant_document_ids'])
    output = {name: full[name][:TOP_K] for name in PROFILES[:2]}
    output['rrf'] = serialize_hits(fused, db_names)
    return {
        'case_id': case['id'], 'category': case['category'], 'query_sha256': canonical_digest(query),
        'annotated_documents': sorted(relevant), 'full_legs': full,
        'violations': {name: check_scope(hits, allowed) for name, hits in (full | {'rrf': output['rrf']}).items()},
        'profiles': {name: {'hits': hits, 'latency_ms': round(durations[name], 3),
                            'scores': ranking_metrics([item['document_id'] for item in hits], relevant)}
                     for name, hits in output.items()},
    }


def summarize(cases, profile):
    rows = [row['profiles'][profile] for row in cases]
    scores = [row['scores'] for row in rows if row['scores'] is not None]
    keys = ('recall_at_5', 'mrr_at_5', 'ndcg_at_5')
    timings = [row['latency_ms'] for row in rows]
    return {'labeled_queries': len(scores),
            **{key: round(sum(score[key] for score in scores) / len(scores), 6) if scores else None for key in keys},
            'unlabeled_queries_with_candidates': sum(row['scores'] is None and bool(row['hits']) for row in rows),
            'latency_ms': {'p50': percentile(timings, .50), 'p95': percentile(timings, .95)}}


def run_suite(connect, environment, suite, document_vectors, query_vectors, source):
    dataset, fixture, db_ids, queries = suite
    password = secrets.token_hex(24)
    with connect('customer_db') as db:
        seed_documents(db, dataset, db_ids, vectors=dict(zip(db_ids, document_vectors, strict=True)), embedding_source=source)
        db.execute(sql.SQL('CREATE ROLE retrieval_benchmark_reader LOGIN PASSWORD {}').format(sql.Literal(password)))
        db.execute('GRANT supportflow_kb_tenant TO retrieval_benchmark_reader')
        postgres = db.execute('SHOW server_version').fetchone()[0]

    def connect_reader():
        return psycopg.connect(host='127.0.0.1', port=environment['SUPPORTFLOW_DB_PORT'], dbname='customer_db',
                               user='retrieval_benchmark_reader', password=password, row_factory=dict_row, connect_timeout=2)

    db_names = {doc_id: name for name, doc_id in db_ids.items()}
    rows = []
    vectors = iter(query_vectors)
    with patch.object(store, 'connect', connect_reader):
        for index, (case, (query, args)) in enumerate(zip(dataset['cases'], queries, strict=True)):
            allowed = allowed_documents(case, dataset['documents'], dataset['as_of'])
            vector = [] if args['doc_ids'] == [] else next(vectors)
            rows.append(measure_case(case, query, args | {'top_k': LEG_DEPTH}, vector, db_names, allowed, dense_first=bool(index % 2)))
    if next(vectors, None) is not None:
        raise ValueError('Unused query embeddings')
    return {'fixture': fixture, 'postgres': postgres, 'cases': rows,
            'metrics': {profile: summarize(rows, profile) for profile in PROFILES}}


def compare(live):
    started_at = datetime.now(timezone.utc).isoformat()
    suites, groups = {}, {}
    for name in SUITES:
        dataset, fixture = load_suite(name)
        db_ids = {doc['id']: 29_000_001 + offset for offset, doc in enumerate(dataset['documents'])}
        queries = [rag_input(case, db_ids) for case in dataset['cases']]
        groups[name + '/documents'] = [doc['text'] for doc in dataset['documents']]
        groups[name + '/queries'] = [query for query, args in queries if args['doc_ids'] != []]
        suites[name] = (dataset, fixture, db_ids, queries)
    if live:
        source = 'qwen:' + EMBEDDING_MODEL
        vectors, receipts = asyncio.run(encode_qwen_groups(groups, qwen_api_key(ROOT / 'ai-service/.env')))
    else:
        source, receipts = 'local-hash', []
        vectors = {name: [embedding._local_vector(text) for text in texts] for name, texts in groups.items()}
    results = {}
    for name, suite in suites.items():
        with disposable_postgres() as (connect, environment):
            with connect('customer_db') as db:
                run_plan(db, load_plan()['customer_db'])
            results[name] = run_suite(connect, environment, suite, vectors[name + '/documents'], vectors[name + '/queries'], source)
    return {
        'schema_version': 1,
        'configuration': {'embedding': source, 'embedding_dimensions': embedding.EMBEDDING_DIM,
                          'candidate_depth': LEG_DEPTH, 'top_k': TOP_K, 'rrf_constant': RRF_CONSTANT,
                          'published_only': True, 'generation_calls': 0, 'score_thresholds': None,
                          'sql_timing': 'alternate leg order; fusion adds both SQL durations and local merge; embedding excluded',
                          'live': {'base_url': BASE_URL, 'attempt_limit': MAX_EMBEDDING_CALLS,
                                   'bytes_per_request': MAX_EMBEDDING_REQUEST_BYTES, 'timeout_seconds': TIMEOUT_SECONDS} if live else None},
        'environment': {'platform': platform.platform(), 'python': platform.python_version(),
                        'git_head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                        'started_at_utc': started_at, 'source_sha256': {
                            path.relative_to(ROOT).as_posix(): sha256(path.read_bytes().replace(b'\r\n', b'\n')).hexdigest()
                            for path in (Path(__file__).resolve(), ROOT / 'scripts/rag_quality_provider.py',
                                         ROOT / 'scripts/rag_quality_benchmark.py', Path(store.__file__), Path(embedding.__file__))}},
        'embedding_receipts': receipts, 'suites': results,
        'violations': [{'suite': name, 'case_id': row['case_id'], 'profile': key, 'out_of_scope': values}
                       for name, suite in results.items() for row in suite['cases']
                       for key, values in row['violations'].items() if values],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--live-qwen', action='store_true', help='Use paid embeddings on the frozen synthetic data')
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Use a new report path')
    try:
        report = compare(args.live_qwen)
    except (ValueError, ModelCallFailure) as error:
        parser.error(str(error))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x', encoding='utf-8') as out:
        json.dump(report, out, ensure_ascii=False, indent=2)
        out.write('\n')
    metrics = {name: suite['metrics'] for name, suite in report['suites'].items()}
    print(json.dumps({'metrics': metrics, 'violations': report['violations'],
                      'embedding_calls': len(report['embedding_receipts'])}, sort_keys=True))
    return int(bool(report['violations']))


if __name__ == '__main__':
    raise SystemExit(main())
