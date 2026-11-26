"""PostgreSQL 17 integration tests for customer_db 013 source_authority governance and RLS."""

from __future__ import annotations

import asyncio
from itertools import count
import json
import os
from pathlib import Path
import secrets
import sys
from types import SimpleNamespace

import psycopg
from psycopg import sql
from psycopg.rows import dict_row
import pytest

ROOT = Path(__file__).resolve().parents[3]
for _extra in (ROOT / "scripts" / "lib", ROOT / "scripts" / "verify" / "fixtures"):
    _path = str(_extra)
    if _path not in sys.path:
        sys.path.insert(0, _path)

from ai.rag import graph, llama_engine, pipeline, reranker, store
from ai.services import rag

IDS = count(34_000_001, 30)
VECTOR = [1.0] + [0.0] * 1023


@pytest.fixture(scope="module")
def pg_dsns():
    """Reuse caller-supplied RLS DSNs or provision an isolated disposable PG17 instance."""
    admin_dsn = os.getenv("SUPPORTFLOW_KB_ADMIN_TEST_DSN")
    kb_dsn = os.getenv("SUPPORTFLOW_KB_TEST_DSN")
    if admin_dsn and kb_dsn:
        yield admin_dsn, kb_dsn
        return

    from isolated_postgres import disposable_postgres
    from migration_engine import load_plan, run_plan

    kb_password = secrets.token_hex(24)
    with disposable_postgres() as (connect, env):
        with connect("customer_db") as db:
            run_plan(db, load_plan()["customer_db"])
            db.execute(
                sql.SQL("CREATE ROLE rls_test_kb_auth LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD {}").format(
                    sql.Literal(kb_password)
                )
            )
            db.execute("GRANT supportflow_kb_tenant TO rls_test_kb_auth")
        port = env["SUPPORTFLOW_DB_PORT"]
        admin_pw = env["SUPPORTFLOW_DB_PASSWORD"]
        yield (
            f"postgresql://supportflow:{admin_pw}@127.0.0.1:{port}/customer_db",
            f"postgresql://rls_test_kb_auth:{kb_password}@127.0.0.1:{port}/customer_db",
        )


def test_source_authority_constraint_default_and_column_privileges(pg_dsns):
    admin_dsn, kb_dsn = pg_dsns
    base = next(IDS)
    tenant = f"SA{base}"

    with psycopg.connect(admin_dsn, autocommit=True) as admin:
        admin.execute(
            """INSERT INTO kb_document (id, tenant_code, doc_no, title, status, index_status, content)
               VALUES (%s, %s, %s, 'Default Official Policy', 3, 3, 'private body')""",
            (base, tenant, f"DOC_{base}"),
        )
        row = admin.execute(
            "SELECT source_authority FROM kb_document WHERE id = %s", (base,)
        ).fetchone()
        assert row == ("official",)

        with pytest.raises(psycopg.errors.CheckViolation):
            admin.execute(
                """INSERT INTO kb_document
                   (id, tenant_code, doc_no, title, status, index_status, source_authority)
                   VALUES (%s, %s, %s, 'Bad Authority', 3, 3, 'forged')""",
                (base + 1, tenant, f"DOC_{base + 1}"),
            )

    with psycopg.connect(kb_dsn, autocommit=True) as kb_conn:
        kb_conn.execute("SELECT set_config('app.tenant_code', %s, false)", (tenant,))
        privs = kb_conn.execute(
            """SELECT
                 has_column_privilege(current_user, 'kb_document', 'source_authority', 'SELECT'),
                 has_column_privilege(current_user, 'kb_document', 'index_status', 'SELECT'),
                 has_column_privilege(current_user, 'kb_document', 'content', 'SELECT'),
                 has_table_privilege(current_user, 'kb_document', 'INSERT,UPDATE,DELETE')"""
        ).fetchone()
        assert privs == (True, True, False, False)
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            kb_conn.execute("SELECT content FROM kb_document WHERE id = %s", (base,))


def test_hybrid_rerank_and_authority_governance_end_to_end_on_postgres(pg_dsns, monkeypatch):
    admin_dsn, kb_dsn = pg_dsns
    base = next(IDS)
    tenant_a = f"HA{base}"
    tenant_b = f"HB{base}"

    docs = {
        "official": base + 1,
        "reference": base + 2,
        "community": base + 3,
        "draft_official": base + 4,
        "foreign_official": base + 5,
    }
    rows = [
        (docs["official"], tenant_a, "正式SLA协议", 3, 3, "official", "企业标准版工单首次响应时限为15分钟。所有正式工单适用。"),
        (docs["reference"], tenant_a, "历史培训参考", 3, 3, "reference", "企业标准版工单首次响应时限为30分钟。所有正式工单适用。"),
        (docs["community"], tenant_a, "社区用户发帖", 3, 3, "community", "网友讨论说企业标准版工单首次响应时限为5分钟。"),
        (docs["draft_official"], tenant_a, "未发布草稿", 1, 3, "official", "企业标准版工单首次响应时限为1分钟。"),
        (docs["foreign_official"], tenant_b, "跨租户SLA", 3, 3, "official", "企业标准版工单首次响应时限为2分钟。"),
    ]
    with psycopg.connect(admin_dsn, autocommit=True) as admin:
        for doc_id, owner, title, status, index_status, authority, text in rows:
            admin.execute(
                """INSERT INTO kb_document
                   (id, tenant_code, doc_no, title, status, index_status, source_authority)
                   VALUES (%s, %s, %s, %s, %s, %s, %s)""",
                (doc_id, owner, f"AUTH_{doc_id}", title, status, index_status, authority),
            )
            admin.execute(
                """INSERT INTO kb_chunk
                   (id, tenant_code, doc_id, chunk_no, content, embedding, embedding_model)
                   VALUES (%s, %s, %s, 1, %s, %s::vector, 'local-hash')""",
                (doc_id, owner, doc_id, text, store.to_pgvector(VECTOR)),
            )

    monkeypatch.setattr(store, "connect", lambda: psycopg.connect(kb_dsn, row_factory=dict_row))
    monkeypatch.setattr(llama_engine, "available", lambda: False)
    monkeypatch.setattr(pipeline.embedding_module, "embed_texts", lambda *_a, **_k: ([VECTOR], "local-hash"))

    settings = SimpleNamespace(
        rag_search_mode="hybrid_rerank",
        rag_hybrid_candidate_depth=15,
        rag_rrf_k=60,
        rag_rerank_mode="lexical_hybrid",
        rag_vector_min_score=0.35,
        rag_keyword_min_score=0.12,
        rag_rerank_min_score=0.15,
        rag_require_grounded_reply=True,
        rag_require_extractive_support=False,
        rag_require_policy_review=True,
        llm_timeout=5,
        rerank_key=lambda: "",
    )
    monkeypatch.setattr(pipeline, "get_settings", lambda: settings)
    monkeypatch.setattr(reranker, "get_settings", lambda: settings)
    monkeypatch.setattr(graph, "get_settings", lambda: settings)
    monkeypatch.setattr(rag, "get_settings", lambda: settings)
    monkeypatch.setattr(graph, "_graph", None)
    monkeypatch.setattr(rag, "resolve_provider", lambda _: ({"model": "fixture"}, "fixture"))

    search_out = pipeline.search(tenant_a, "企业标准版工单首次响应时限是多少分钟", top_k=5, published_only=True)
    assert search_out["mode"] == "hybrid_rerank"
    returned_docs = [hit["doc_id"] for hit in search_out["results"]]
    assert docs["draft_official"] not in returned_docs
    assert docs["foreign_official"] not in returned_docs
    assert returned_docs[0] == docs["official"]
    authorities = {hit["doc_id"]: hit["source_authority"] for hit in search_out["results"]}
    assert authorities == {
        docs["official"]: "official",
        docs["reference"]: "reference",
        docs["community"]: "community",
    }

    # 1) Community-only scope in grounded visitor mode -> abstain without LLM call
    monkeypatch.setattr(rag, "call_chat", lambda *_a, **_k: pytest.fail("LLM must not run for community-only hits"))
    comm_only = asyncio.run(
        graph.ask(
            "企业标准版工单首次响应时限是多少分钟？",
            tenant_a,
            doc_ids=[docs["community"]],
            published_only=True,
        )
    )
    assert comm_only["decision"] == "abstain"
    assert comm_only["abstention_reason"] == "insufficient_authority"
    assert comm_only["citations"] == []

    # 2) Official + conflicting reference + community -> official wins cleanly (no false policy_conflict)
    async def answer_official(_provider, payload, _timeout, **_kw):
        sources = json.loads(payload["messages"][1]["content"])["sources"]
        assert len(sources) == 1
        assert "15分钟" in sources[0]["content"]
        reply = {
            "decision": "answer",
            "claims": [
                {
                    "text": "企业标准版工单首次响应时限为15分钟。",
                    "sources": [{"index": 1, "quote": "企业标准版工单首次响应时限为15分钟。"}],
                }
            ],
        }
        return {"choices": [{"message": {"content": json.dumps(reply, ensure_ascii=False)}}]}

    monkeypatch.setattr(rag, "call_chat", answer_official)
    resolved = asyncio.run(
        graph.ask(
            "企业标准版工单首次响应时限是多少分钟？",
            tenant_a,
            published_only=True,
        )
    )
    assert resolved["decision"] == "answer"
    assert resolved["handoff_recommended"] is False
    assert "15分钟" in resolved["answer"]
    assert [c["doc_id"] for c in resolved["citations"]] == [str(docs["official"])]

    # 3) Mid-flight authority downgrade from official -> community triggers stale_evidence on recheck
    async def downgrade_during_generation(_provider, _payload, _timeout, **_kw):
        with psycopg.connect(admin_dsn, autocommit=True) as admin:
            admin.execute(
                "UPDATE kb_document SET source_authority = 'community' WHERE id = %s",
                (docs["official"],),
            )
        reply = {
            "decision": "answer",
            "claims": [
                {
                    "text": "企业标准版工单首次响应时限为15分钟。",
                    "sources": [{"index": 1, "quote": "企业标准版工单首次响应时限为15分钟。"}],
                }
            ],
        }
        return {"choices": [{"message": {"content": json.dumps(reply, ensure_ascii=False)}}]}

    monkeypatch.setattr(rag, "call_chat", downgrade_during_generation)
    downgraded = asyncio.run(
        graph.ask(
            "企业标准版工单首次响应时限是多少分钟？",
            tenant_a,
            doc_ids=[docs["official"]],
            published_only=True,
        )
    )
    assert downgraded["decision"] == "abstain"
    assert downgraded["abstention_reason"] == "stale_evidence"
    assert downgraded["citations"] == []
