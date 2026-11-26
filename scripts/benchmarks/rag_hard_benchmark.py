"""High-discrimination RAG 2.0 retrieval and grounded QA benchmark on isolated PostgreSQL 17."""

from __future__ import annotations

import sys as _sys
from pathlib import Path as _Path

_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)

import argparse
import asyncio
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from hashlib import sha256
import json
import logging
from math import ceil, log2
from pathlib import Path
import platform
import re
import secrets
import subprocess
import sys
from time import perf_counter
from types import SimpleNamespace
from typing import Any
from unittest.mock import patch

import psycopg
from psycopg import sql
from psycopg.rows import dict_row

from isolated_postgres import disposable_postgres
from migration_engine import load_plan, run_plan

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT / "ai-service") not in sys.path:
    sys.path.insert(0, str(ROOT / "ai-service"))

from ai.rag import embedding, graph, grounded_reply, llama_engine, pipeline, reranker, retriever, splitter, store
from ai.services import rag
from rag_quality_provider import (
    EMBEDDING_MODEL,
    ModelCallFailure,
    QwenGeneration,
    encode_qwen_groups,
    qwen_api_key,
)

FIXTURE_PATH = ROOT / "fixtures/evaluation/rag-hard-benchmark-v1.json"
RETRIEVAL_PROFILES = ("keyword", "vector", "hybrid_rrf", "hybrid_rerank")
TOP_K = 5
CANDIDATE_DEPTH = 15
RRF_K = 60
CHUNK_SIZE = 260
CHUNK_OVERLAP = 25
VALID_AUTHORITIES = {"official", "reference", "community"}
VALID_OUTCOMES = {"answer", "abstain", "handoff"}
IDENTIFIER_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")


class BenchmarkDatasetError(ValueError):
    """Raised when rag-hard-benchmark-v1.json fails schema or SHA-256 verification."""


def canonical_digest(value: Any) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return sha256(encoded).hexdigest()


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    obj = dict(pairs)
    if len(obj) != len(pairs):
        raise BenchmarkDatasetError("Duplicate JSON keys in benchmark fixture")
    return obj


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, ceil(len(ordered) * fraction) - 1))
    return round(ordered[index], 3)


def ranking_metrics(ranked_doc_ids: list[str], relevant_doc_ids: set[str], k: int = TOP_K) -> dict[str, float] | None:
    """Compute Recall@K, MRR@K, and NDCG@K over unique document hits."""
    if not relevant_doc_ids:
        return None
    first_rank: int | None = None
    gained: set[str] = set()
    dcg = 0.0
    for rank, doc_id in enumerate(ranked_doc_ids[:k], start=1):
        if doc_id in relevant_doc_ids and doc_id not in gained:
            if first_rank is None:
                first_rank = rank
            gained.add(doc_id)
            dcg += 1.0 / log2(rank + 1)
    ideal_hits = min(len(relevant_doc_ids), k)
    idcg = sum(1.0 / log2(rank + 1) for rank in range(1, ideal_hits + 1))
    return {
        "recall_at_5": round(len(gained) / len(relevant_doc_ids), 6),
        "mrr_at_5": round(1.0 / first_rank, 6) if first_rank else 0.0,
        "ndcg_at_5": round(dcg / idcg, 6) if idcg > 0 else 0.0,
    }


def validate_hard_dataset(dataset: dict[str, Any]) -> dict[str, Any]:
    expected_top_keys = {"version", "corpus_version", "mode", "chunk_size", "chunk_overlap", "documents", "questions"}
    if not isinstance(dataset, dict) or set(dataset.keys()) != expected_top_keys:
        raise BenchmarkDatasetError("Top-level keys mismatch in rag-hard-benchmark-v1")
    if dataset["version"] != "rag-hard-benchmark-v1" or dataset["mode"] != "synthetic_enterprise":
        raise BenchmarkDatasetError("Invalid dataset version or mode")

    docs = dataset["documents"]
    questions = dataset["questions"]
    if not isinstance(docs, list) or len(docs) != 24:
        raise BenchmarkDatasetError(f"Expected 24 documents, got {len(docs) if isinstance(docs, list) else 'non-list'}")
    if not isinstance(questions, list) or len(questions) != 36:
        raise BenchmarkDatasetError(
            f"Expected 36 questions, got {len(questions) if isinstance(questions, list) else 'non-list'}"
        )

    doc_map: dict[str, dict[str, Any]] = {}
    doc_keys = {"id", "tenant", "title", "source_authority", "publication", "index_status", "text"}
    total_chunks = 0
    for doc in docs:
        if not isinstance(doc, dict) or set(doc.keys()) != doc_keys:
            raise BenchmarkDatasetError(f"Invalid document schema: {doc}")
        doc_id = doc["id"]
        if not IDENTIFIER_RE.match(str(doc_id)) or doc_id in doc_map:
            raise BenchmarkDatasetError(f"Invalid or duplicate doc id: {doc_id}")
        if doc["tenant"] not in {"HARD_A", "HARD_B"}:
            raise BenchmarkDatasetError(f"Unsupported tenant: {doc['tenant']}")
        if doc["source_authority"] not in VALID_AUTHORITIES:
            raise BenchmarkDatasetError(f"Invalid source_authority: {doc['source_authority']}")
        if doc["publication"] not in {"published", "draft"}:
            raise BenchmarkDatasetError(f"Invalid publication: {doc['publication']}")
        if doc["index_status"] not in {"ready", "failed"}:
            raise BenchmarkDatasetError(f"Invalid index_status: {doc['index_status']}")
        if not str(doc["title"]).strip() or not str(doc["text"]).strip():
            raise BenchmarkDatasetError(f"Empty title or text in {doc_id}")
        chunks = splitter.split_text(
            doc["text"],
            chunk_size=int(dataset["chunk_size"]),
            chunk_overlap=int(dataset["chunk_overlap"]),
        )
        if not chunks:
            raise BenchmarkDatasetError(f"Document produced zero chunks: {doc_id}")
        total_chunks += len(chunks)
        doc_map[doc_id] = doc

    q_keys = {
        "id",
        "tenant",
        "category",
        "question",
        "document_scope",
        "relevant_document_ids",
        "expected_outcome",
    }
    seen_q: set[str] = set()
    for item in questions:
        if not isinstance(item, dict) or set(item.keys()) != q_keys:
            raise BenchmarkDatasetError(f"Invalid question schema: {item}")
        qid = item["id"]
        if not IDENTIFIER_RE.match(str(qid)) or qid in seen_q:
            raise BenchmarkDatasetError(f"Invalid or duplicate question id: {qid}")
        seen_q.add(qid)
        if item["tenant"] not in {"HARD_A", "HARD_B"}:
            raise BenchmarkDatasetError(f"Invalid question tenant: {qid}")
        if not str(item["question"]).strip():
            raise BenchmarkDatasetError(f"Empty question text: {qid}")
        if item["expected_outcome"] not in VALID_OUTCOMES:
            raise BenchmarkDatasetError(f"Invalid expected_outcome in {qid}")
        scope = item["document_scope"]
        if scope is not None:
            if not isinstance(scope, list) or any(d not in doc_map for d in scope):
                raise BenchmarkDatasetError(f"Invalid document_scope in {qid}")
        rel = item["relevant_document_ids"]
        if not isinstance(rel, list) or len(set(rel)) != len(rel):
            raise BenchmarkDatasetError(f"Invalid relevant_document_ids in {qid}")
        for rel_id in rel:
            if rel_id not in doc_map:
                raise BenchmarkDatasetError(f"Unknown relevant doc {rel_id} in {qid}")
            target = doc_map[rel_id]
            if (
                target["tenant"] != item["tenant"]
                or target["publication"] != "published"
                or target["index_status"] != "ready"
                or target["source_authority"] == "community"
            ):
                raise BenchmarkDatasetError(f"Ineligible relevant doc {rel_id} in {qid}")

    return {
        "version": dataset["version"],
        "corpus_version": dataset["corpus_version"],
        "document_count": len(docs),
        "chunk_count": total_chunks,
        "question_count": len(questions),
        "tenant_documents": dict(Counter(d["tenant"] for d in docs)),
        "tenant_questions": dict(Counter(q["tenant"] for q in questions)),
        "authority_counts": dict(Counter(d["source_authority"] for d in docs)),
        "category_counts": dict(Counter(q["category"] for q in questions)),
    }


def load_hard_fixture(path: Path = FIXTURE_PATH) -> tuple[dict[str, Any], dict[str, Any]]:
    dataset = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_reject_duplicate_keys)
    summary = validate_hard_dataset(dataset)
    digest = canonical_digest(dataset)
    sha_path = path.with_suffix(".sha256")
    expected = sha_path.read_text(encoding="ascii").strip()
    if digest != expected:
        raise BenchmarkDatasetError(f"Dataset SHA-256 mismatch: expected {expected}, got {digest}")
    return dataset, summary | {"dataset_sha256": digest}


def allowed_published_docs(question_item: dict[str, Any], documents: list[dict[str, Any]]) -> set[str]:
    scope = question_item["document_scope"]
    scope_set = None if scope is None else set(scope)
    return {
        doc["id"]
        for doc in documents
        if doc["tenant"] == question_item["tenant"]
        and doc["publication"] == "published"
        and doc["index_status"] == "ready"
        and (scope_set is None or doc["id"] in scope_set)
    }


def _fixture_grounded_reply(payload: dict[str, Any]) -> dict[str, Any]:
    """Deterministic grounded responder using only the first prompt source."""
    user_json = json.loads(payload["messages"][-1]["content"])
    sources = user_json.get("sources") or []
    if not sources:
        return {"choices": [{"message": {"content": '{"decision":"abstain"}'}}]}
    for src in sources:
        for sentence in grounded_reply.source_sentences(src["content"]):
            if grounded_reply.MANUAL_REVIEW.search(sentence):
                body = {"decision": "handoff", "sources": [{"index": src["index"], "quote": sentence}]}
                return {"choices": [{"message": {"content": json.dumps(body, ensure_ascii=False)}}]}
    top_src = sources[0]
    first_sentence = next(grounded_reply.source_sentences(top_src["content"]), top_src["content"].strip())
    body = {
        "decision": "answer",
        "claims": [
            {
                "text": first_sentence[:240],
                "sources": [{"index": top_src["index"], "quote": first_sentence}],
            }
        ],
    }
    return {"choices": [{"message": {"content": json.dumps(body, ensure_ascii=False)}}]}


def seed_hard_corpus(
    connection: psycopg.Connection,
    dataset: dict[str, Any],
    db_ids: dict[str, int],
    chunk_vectors: dict[tuple[str, int], list[float]],
    embedding_source: str,
) -> int:
    chunk_id_seq = 45_000_001
    total_chunks = 0
    c_size = int(dataset["chunk_size"])
    c_overlap = int(dataset["chunk_overlap"])
    for doc in dataset["documents"]:
        logical_id = doc["id"]
        doc_id = db_ids[logical_id]
        status = 3 if doc["publication"] == "published" else 1
        index_status = 3 if doc["index_status"] == "ready" else 4
        connection.execute(
            """INSERT INTO kb_document
               (id, tenant_code, doc_no, title, status, index_status, source_authority)
               VALUES (%s, %s, %s, %s, %s, %s, %s)""",
            (doc_id, doc["tenant"], logical_id.upper(), doc["title"], status, index_status, doc["source_authority"]),
        )
        chunks = splitter.split_text(doc["text"], chunk_size=c_size, chunk_overlap=c_overlap)
        for chunk in chunks:
            vec = chunk_vectors[(logical_id, chunk.index)]
            connection.execute(
                """INSERT INTO kb_chunk
                   (id, tenant_code, doc_id, chunk_no, content, char_count, token_count, embedding, embedding_model)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s::vector, %s)""",
                (
                    chunk_id_seq,
                    doc["tenant"],
                    doc_id,
                    chunk.index,
                    chunk.content,
                    chunk.char_count,
                    chunk.token_count,
                    store.to_pgvector(vec),
                    embedding_source,
                ),
            )
            chunk_id_seq += 1
            total_chunks += 1
    return total_chunks


def _evaluate_single_retrieval_question(
    item: dict[str, Any],
    query_vector: list[float],
    db_ids: dict[str, int],
    logical_by_db: dict[int, str],
    doc_meta: dict[str, dict[str, Any]],
    allowed: set[str],
    live_rerank: bool,
) -> dict[str, Any]:
    tenant = item["tenant"]
    query = item["question"]
    scope = None if item["document_scope"] is None else [db_ids[d] for d in item["document_scope"]]
    relevant = set(item["relevant_document_ids"])

    profile_outputs: dict[str, dict[str, Any]] = {}
    leakage: dict[str, dict[str, list[str]]] = {}

    for profile in RETRIEVAL_PROFILES:
        started = perf_counter()
        if profile == "keyword":
            hits = store.keyword_search(tenant, query, top_k=TOP_K, doc_ids=scope, published_only=True)
        elif profile == "vector":
            hits = store.search(tenant, query_vector, top_k=TOP_K, doc_ids=scope, published_only=True)
        elif profile == "hybrid_rrf":
            hits = store.hybrid_search(
                tenant,
                query,
                query_vector,
                top_k=TOP_K,
                candidate_depth=CANDIDATE_DEPTH,
                rrf_k=RRF_K,
                doc_ids=scope,
                published_only=True,
            )
        else:
            candidates = store.hybrid_search(
                tenant,
                query,
                query_vector,
                top_k=CANDIDATE_DEPTH,
                candidate_depth=CANDIDATE_DEPTH,
                rrf_k=RRF_K,
                doc_ids=scope,
                published_only=True,
            )
            reranked = reranker.rerank_candidates(
                query,
                candidates,
                top_k=TOP_K,
                tenant_code=tenant,
                mode="qwen" if live_rerank else "lexical_hybrid",
            )
            hits = reranked["results"]
        latency_ms = round((perf_counter() - started) * 1000, 3)

        ranked_docs = [logical_by_db[h["doc_id"]] for h in hits]
        cross_tenant = [
            doc_id for doc_id in ranked_docs if doc_meta[doc_id]["tenant"] != tenant
        ]
        unpublished = [
            doc_id
            for doc_id in ranked_docs
            if doc_id not in allowed and doc_meta[doc_id]["tenant"] == tenant
        ]
        leakage[profile] = {"cross_tenant": cross_tenant, "unpublished": unpublished}
        profile_outputs[profile] = {
            "latency_ms": latency_ms,
            "hits": [
                {
                    "document_id": logical_by_db[h["doc_id"]],
                    "chunk_no": h["chunk_no"],
                    "score": h["score"],
                    "source_authority": h.get("source_authority", "official"),
                }
                for h in hits
            ],
            "metrics": ranking_metrics(ranked_docs, relevant, k=TOP_K),
        }

    return {
        "id": item["id"],
        "tenant": tenant,
        "category": item["category"],
        "relevant_document_ids": sorted(relevant),
        "leakage": leakage,
        "profiles": profile_outputs,
    }


def summarize_retrieval_profile(cases: list[dict[str, Any]], profile: str) -> dict[str, Any]:
    rows = [c["profiles"][profile] for c in cases]
    scored = [r["metrics"] for r in rows if r["metrics"] is not None]
    latencies = [r["latency_ms"] for r in rows]
    cross_leak = sum(len(c["leakage"][profile]["cross_tenant"]) for c in cases)
    unpub_leak = sum(len(c["leakage"][profile]["unpublished"]) for c in cases)
    return {
        "eligible_questions": len(scored),
        "recall_at_5": round(sum(m["recall_at_5"] for m in scored) / len(scored), 6) if scored else 0.0,
        "mrr_at_5": round(sum(m["mrr_at_5"] for m in scored) / len(scored), 6) if scored else 0.0,
        "ndcg_at_5": round(sum(m["ndcg_at_5"] for m in scored) / len(scored), 6) if scored else 0.0,
        "cross_tenant_leakage": cross_leak,
        "unpublished_leakage": unpub_leak,
        "latency_ms": {"p50": percentile(latencies, 0.50), "p95": percentile(latencies, 0.95)},
    }


async def run_qa_benchmark(
    questions: list[dict[str, Any]],
    documents: list[dict[str, Any]],
    db_ids: dict[str, int],
    logical_by_db: dict[int, str],
    *,
    workers: int,
    live_gen: QwenGeneration | None = None,
) -> dict[str, Any]:
    semaphore = asyncio.Semaphore(max(1, workers))

    async def fake_or_live_chat(_provider: Any, payload: dict[str, Any], _timeout: int, **_kw: Any) -> dict[str, Any]:
        if live_gen is not None:
            receipt: dict[str, Any] = {"attempted": False}
            return await live_gen.generate(payload, receipt, scene="RAG硬基准评测")
        return _fixture_grounded_reply(payload)

    async def run_one(item: dict[str, Any]) -> dict[str, Any]:
        scope = None if item["document_scope"] is None else [db_ids[d] for d in item["document_scope"]]
        allowed = allowed_published_docs(item, documents)
        async with semaphore:
            started = perf_counter()
            res = await graph.ask(
                item["question"],
                item["tenant"],
                top_k=TOP_K,
                doc_ids=scope,
                published_only=True,
                search_mode="hybrid_rerank",
            )
            elapsed_ms = round((perf_counter() - started) * 1000, 3)

        cited_docs = [logical_by_db[int(c["doc_id"])] for c in res.get("citations", [])]
        unauthorized = [doc_id for doc_id in cited_docs if doc_id not in allowed]
        observed_outcome = (
            "handoff" if res.get("handoff_recommended") else str(res.get("decision") or "abstain")
        )
        return {
            "id": item["id"],
            "latency_ms": elapsed_ms,
            "decision": res.get("decision"),
            "observed_outcome": observed_outcome,
            "expected_outcome": item["expected_outcome"],
            "outcome_match": observed_outcome == item["expected_outcome"],
            "abstention_reason": res.get("abstention_reason"),
            "cited_documents": cited_docs,
            "unauthorized_citations": unauthorized,
        }

    with patch.object(rag, "call_chat", fake_or_live_chat):
        qa_rows = await asyncio.gather(*(run_one(q) for q in questions))

    latencies = [row["latency_ms"] for row in qa_rows]
    unauthorized_total = sum(len(row["unauthorized_citations"]) for row in qa_rows)
    return {
        "total_questions": len(qa_rows),
        "outcome_matches": sum(1 for row in qa_rows if row["outcome_match"]),
        "decisions": dict(Counter(row["observed_outcome"] for row in qa_rows)),
        "unauthorized_citations": unauthorized_total,
        "latency_ms": {"p50": percentile(latencies, 0.50), "p95": percentile(latencies, 0.95)},
        "cases": list(qa_rows),
    }


def run_hard_benchmark(*, live_qwen: bool = False, workers: int = 4, fixture_path: Path = FIXTURE_PATH) -> dict[str, Any]:
    workers = max(1, min(int(workers), 16))
    started_utc = datetime.now(timezone.utc).isoformat()
    dataset, fixture_meta = load_hard_fixture(fixture_path)
    docs = dataset["documents"]
    questions = dataset["questions"]
    doc_meta = {d["id"]: d for d in docs}
    db_ids = {d["id"]: 41_000_001 + idx for idx, d in enumerate(docs)}
    logical_by_db = {v: k for k, v in db_ids.items()}

    c_size = int(dataset["chunk_size"])
    c_overlap = int(dataset["chunk_overlap"])
    chunk_keys: list[tuple[str, int]] = []
    chunk_texts: list[str] = []
    for doc in docs:
        for ch in splitter.split_text(doc["text"], chunk_size=c_size, chunk_overlap=c_overlap):
            chunk_keys.append((doc["id"], ch.index))
            chunk_texts.append(ch.content)
    query_texts = [q["question"] for q in questions]

    live_gen: QwenGeneration | None = None
    api_key = ""
    if live_qwen:
        api_key = qwen_api_key(ROOT / "ai-service/.env")
        embedding_source = f"qwen:{EMBEDDING_MODEL}"
        encoded, embedding_receipts = asyncio.run(
            encode_qwen_groups({"chunks": chunk_texts, "queries": query_texts}, api_key)
        )
        chunk_vecs_list = encoded["chunks"]
        query_vecs_list = encoded["queries"]
        live_gen = QwenGeneration(api_key)
    else:
        embedding_source = "local-hash"
        embedding_receipts = []
        chunk_vecs_list = [embedding._local_vector(t) for t in chunk_texts]
        query_vecs_list = [embedding._local_vector(t) for t in query_texts]

    chunk_vectors = dict(zip(chunk_keys, chunk_vecs_list, strict=True))
    query_vectors_by_q = {q["question"]: vec for q, vec in zip(questions, query_vecs_list, strict=True)}

    kb_password = secrets.token_hex(24)
    with disposable_postgres() as (connect, env):
        with connect("customer_db") as admin_conn:
            run_plan(admin_conn, load_plan()["customer_db"])
            with admin_conn.transaction():
                seeded_chunks = seed_hard_corpus(
                    admin_conn, dataset, db_ids, chunk_vectors, embedding_source
                )
                admin_conn.execute(
                    sql.SQL("CREATE ROLE hard_bench_reader LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD {}").format(
                        sql.Literal(kb_password)
                    )
                )
                admin_conn.execute("GRANT supportflow_kb_tenant TO hard_bench_reader")
            pg_version = admin_conn.execute("SHOW server_version").fetchone()[0]

        def connect_reader():
            return psycopg.connect(
                host="127.0.0.1",
                port=env["SUPPORTFLOW_DB_PORT"],
                dbname="customer_db",
                user="hard_bench_reader",
                password=kb_password,
                row_factory=dict_row,
                connect_timeout=3,
            )

        bench_settings = SimpleNamespace(
            rag_search_mode="hybrid_rerank",
            rag_hybrid_candidate_depth=CANDIDATE_DEPTH,
            rag_rrf_k=RRF_K,
            rag_rerank_mode="qwen" if live_qwen else "lexical_hybrid",
            rag_rerank_url="https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank",
            rag_rerank_model="gte-rerank",
            rag_rerank_timeout=5.0,
            rag_rerank_max_response_bytes=262144,
            rag_vector_min_score=0.35,
            rag_keyword_min_score=0.12,
            rag_rerank_min_score=0.15,
            rag_require_grounded_reply=True,
            rag_require_extractive_support=False,
            rag_require_policy_review=True,
            llm_timeout=15 if live_qwen else 5,
            rerank_key=lambda: api_key,
        )

        def fake_embed_texts(texts: list[str], **_kw: Any) -> tuple[list[list[float]], str]:
            return [query_vectors_by_q.get(t) or embedding._local_vector(t) for t in texts], embedding_source

        provider_tuple = (
            (live_gen.chosen, "qwen-live")
            if live_gen is not None
            else ({"model": "fixture-grounded-v1"}, "offline-fixture")
        )

        with (
            patch.object(store, "connect", connect_reader),
            patch.object(llama_engine, "available", return_value=False),
            patch.object(embedding, "embed_texts", fake_embed_texts),
            patch.object(pipeline, "get_settings", return_value=bench_settings),
            patch.object(reranker, "get_settings", return_value=bench_settings),
            patch.object(graph, "get_settings", return_value=bench_settings),
            patch.object(graph, "_graph", None),
            patch.object(rag, "get_settings", return_value=bench_settings),
            patch.object(rag, "resolve_provider", return_value=provider_tuple),
        ):
            with ThreadPoolExecutor(max_workers=workers) as pool:
                futures = [
                    pool.submit(
                        _evaluate_single_retrieval_question,
                        q,
                        query_vecs_list[idx],
                        db_ids,
                        logical_by_db,
                        doc_meta,
                        allowed_published_docs(q, docs),
                        live_qwen,
                    )
                    for idx, q in enumerate(questions)
                ]
                retrieval_cases = [fut.result() for fut in futures]

            qa_summary = asyncio.run(
                run_qa_benchmark(
                    questions,
                    docs,
                    db_ids,
                    logical_by_db,
                    workers=workers,
                    live_gen=live_gen,
                )
            )

    retrieval_metrics = {
        profile: summarize_retrieval_profile(retrieval_cases, profile)
        for profile in RETRIEVAL_PROFILES
    }
    violations: list[str] = []
    for profile, stats in retrieval_metrics.items():
        if stats["cross_tenant_leakage"] != 0:
            violations.append(f"{profile}: cross-tenant leakage detected")
        if stats["unpublished_leakage"] != 0:
            violations.append(f"{profile}: unpublished/unindexed leakage detected")
    if qa_summary["unauthorized_citations"] != 0:
        violations.append("qa: unauthorized citation detected")

    return {
        "schema_version": 1,
        "fixture": fixture_meta | {"seeded_chunks": seeded_chunks},
        "configuration": {
            "top_k": TOP_K,
            "candidate_depth": CANDIDATE_DEPTH,
            "rrf_k": RRF_K,
            "workers": workers,
            "live_qwen": live_qwen,
            "embedding_source": embedding_source,
            "rerank_mode": "qwen:gte-rerank" if live_qwen else "lexical_hybrid",
        },
        "environment": {
            "platform": platform.platform(),
            "python": platform.python_version(),
            "postgres": pg_version,
            "started_at_utc": started_utc,
            "git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        },
        "embedding_receipts": embedding_receipts,
        "retrieval_metrics": retrieval_metrics,
        "qa_summary": {k: v for k, v in qa_summary.items() if k != "cases"},
        "violations": violations,
        "retrieval_cases": retrieval_cases,
        "qa_cases": qa_summary["cases"],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="Output path for the JSON benchmark report")
    parser.add_argument("--live-qwen", action="store_true", help="Run with live DashScope embeddings, gte-rerank, and Qwen")
    parser.add_argument("--workers", type=int, default=4, help="Controlled concurrency worker count (1..16)")
    args = parser.parse_args()

    logging.basicConfig(level=logging.ERROR)
    if args.live_qwen and args.output.exists():
        parser.error("Refusing to overwrite an existing live report; choose a new --output path")

    try:
        report = run_hard_benchmark(live_qwen=args.live_qwen, workers=args.workers)
    except (BenchmarkDatasetError, ModelCallFailure, ValueError) as exc:
        parser.error(str(exc))
        return 2

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    summary_out = {
        "fixture": {
            "documents": report["fixture"]["document_count"],
            "chunks": report["fixture"]["seeded_chunks"],
            "questions": report["fixture"]["question_count"],
        },
        "retrieval_metrics": report["retrieval_metrics"],
        "qa_summary": report["qa_summary"],
        "violations": report["violations"],
    }
    print(json.dumps(summary_out, ensure_ascii=False, sort_keys=True))
    return 1 if report["violations"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
