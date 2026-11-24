from concurrent.futures import ThreadPoolExecutor
import os
from time import monotonic, sleep
from types import SimpleNamespace

import psycopg
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from psycopg.rows import dict_row

from ai.api import kb


def test_kb_status_requires_a_tenant_before_accessing_the_store(monkeypatch):
    app = FastAPI()
    app.include_router(kb.router, prefix="/api")

    def unexpected_store_access():
        raise AssertionError("Unauthorized diagnostics must not query the store")

    monkeypatch.setattr(kb.store, "ping", unexpected_store_access)
    with TestClient(app) as client:
        for headers in ({}, {"X-Tenant-Code": "PLATFORM"}):
            assert client.get("/api/ai/v1/kb/status", headers=headers).status_code == 403


def test_kb_status_queries_only_the_selected_tenant_without_returning_document_ids(monkeypatch):
    app = FastAPI()
    app.include_router(kb.router, prefix="/api")
    monkeypatch.setattr(kb.store, "ping", lambda: True)
    queries = []

    class Connection:
        def __enter__(self):
            return self

        def __exit__(self, *_):
            pass

        def execute(self, statement, params):
            queries.append((statement, params))
            return self

        def fetchall(self):
            return [{"model": "local-hash", "chunks": 2}]

    monkeypatch.setattr(kb.store, "tenant_connection", lambda tenant: Connection())
    with TestClient(app) as client:
        response = client.get("/api/ai/v1/kb/status", headers={"X-Tenant-Code": "TENANT_A"})
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["chunkVectorModels"] == [{"model": "local-hash", "chunks": 2}]
    assert "fallbackDocIds" not in data
    assert len(queries) == 1
    statement, params = queries[0]
    assert "tenant_code = %s" in statement
    assert params == ("TENANT_A",)


@pytest.mark.skipif(not os.getenv("SUPPORTFLOW_KB_TEST_DSN"), reason="run scripts/test-customer-rls.py --with-ai")
def test_console_status_uses_the_real_chunk_schema_and_separates_tenants(monkeypatch):
    app = FastAPI()
    app.include_router(kb.router, prefix="/api")
    monkeypatch.setattr(kb.store, "connect", lambda: psycopg.connect(
        os.environ["SUPPORTFLOW_KB_TEST_DSN"], row_factory=dict_row))
    with TestClient(app) as client:
        a = client.get("/api/ai/v1/kb/status", headers={"X-Tenant-Code": "TENANT_A"})
        b = client.get("/api/ai/v1/kb/status", headers={"X-Tenant-Code": "TENANT_B"})
        assert a.status_code == b.status_code == 200
        assert a.json()["data"]["chunkVectorModels"] == [{"model": "local-hash", "chunks": 1}]
        assert b.json()["data"]["chunkVectorModels"] == [{"model": "test-embedding", "chunks": 1}]
        assert "fallbackDocIds" not in a.json()["data"]
        for tenant, ids in (("TENANT_A", ["1"]), ("TENANT_B", [])):
            repair = client.get("/api/ai/v1/kb/repair-status", headers={"X-Tenant-Code": tenant})
            assert repair.json()["fallbackDocIds"] == ids


@pytest.mark.skipif(not os.getenv("SUPPORTFLOW_KB_TEST_DSN"), reason="run scripts/test-customer-rls.py --with-ai")
def test_knowledge_store_uses_restricted_role_for_search_and_mutations(monkeypatch):
    store = kb.store
    monkeypatch.setattr(store, "connect", lambda: psycopg.connect(
        os.environ["SUPPORTFLOW_KB_TEST_DSN"], row_factory=dict_row))
    assert store.ping()
    with store.connect() as conn:
        assert conn.execute("SELECT doc_id FROM kb_chunk").fetchall() == []
    assert [row["doc_title"] for row in store.keyword_search("TENANT_A", "delivery")] == ["Delivery A"]
    assert [row["doc_title"] for row in store.keyword_search("TENANT_B", "delivery")] == ["Delivery B"]
    assert store.delete_chunks("TENANT_B", 1) == 0
    chunk = SimpleNamespace(index=0, content="delivery updated", char_count=16, token_count=3)
    vector = [1.0] + [0.0] * 1023
    assert store.replace_chunks("TENANT_A", 1, [chunk], [vector], "unit-embedding") == 1
    assert [row["content"] for row in store.search("TENANT_A", vector)] == ["delivery updated"]
    assert store.search("TENANT_B", vector) == []
    assert store.list_chunks("TENANT_A", 1)[0]["content"] == "delivery updated"
    assert store.list_chunks("TENANT_B", 1) == []
    assert store.delete_chunks("TENANT_A", 1) == 1
    assert len(store.list_chunks("TENANT_B", 2)) == 1


@pytest.mark.skipif(not os.getenv("SUPPORTFLOW_KB_TEST_DSN"), reason="run scripts/test-customer-rls.py --with-ai")
@pytest.mark.parametrize("operation", ["replace", "delete"])
def test_chunk_mutation_waits_for_same_document_writer_with_restricted_role(monkeypatch, operation):
    store = kb.store
    monkeypatch.setattr(store, "connect", lambda: psycopg.connect(
        os.environ["SUPPORTFLOW_KB_TEST_DSN"], row_factory=dict_row, connect_timeout=5,
        options="-c lock_timeout=8000"))
    tenant = "TENANT_A"
    doc = 9130001 if operation == "replace" else 9130002
    independent_doc = doc + 10
    chunk = SimpleNamespace(index=1, content="second version", char_count=14, token_count=2)
    try:
        with ThreadPoolExecutor(max_workers=1) as executor:
            with store.tenant_connection(tenant) as blocker:
                blocker.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                                (f"supportflow:kb-chunks:{tenant}:{doc}",))
                if operation == "replace":
                    pending = executor.submit(store.replace_chunks, tenant, doc, [chunk], [[]], "fixture")
                else:
                    pending = executor.submit(store.delete_chunks, tenant, doc)
                deadline = monotonic() + 5
                while True:
                    waiting = blocker.execute("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_locks held JOIN pg_locks waiter USING (locktype, classid, objid, objsubid)
                            WHERE held.pid = pg_backend_pid() AND held.locktype = 'advisory'
                              AND held.granted AND NOT waiter.granted
                        ) AS waiting
                    """).fetchone()["waiting"]
                    if waiting:
                        break
                    assert not pending.done() and monotonic() < deadline, "same-document writer did not wait"
                    sleep(.02)
                assert store.replace_chunks(tenant, independent_doc, [chunk], [[]], "fixture") == 1
                blocker.execute("""
                    INSERT INTO kb_chunk (id, tenant_code, doc_id, chunk_no, content)
                    VALUES (%s, %s, %s, 1, 'first version')
                """, (store.next_id(), tenant, doc))
            assert pending.result(timeout=8) == 1
        contents = [row["content"] for row in store.list_chunks(tenant, doc)]
        assert contents == (["second version"] if operation == "replace" else [])
    finally:
        store.delete_chunks(tenant, doc)
        store.delete_chunks(tenant, independent_doc)
