import hmac
import time

import pytest
from fastapi.testclient import TestClient

from ai import main
from ai.config import Settings
from ai.api import brain, kb


KEYS = {role: f"isolated-test-only-{role}-0000000000000000000000000"
        for role in ("customer", "gateway", "tenant", "ops")}
KB = "/api/ai/v1/kb"
BRAIN = "/api/ai/v1/agent/brain"


@pytest.fixture
def client(monkeypatch):
    settings = Settings(_env_file=None, **{f"{role}_secret": key for role, key in KEYS.items()})
    monkeypatch.setattr(main, "get_settings", lambda: settings)
    monkeypatch.setattr(main, "ensure_schema", lambda: None)
    with TestClient(main.create_app()) as test_client:
        yield test_client


def signed(method, path, tenant="TENANT_A", caller="customer", expires=None, key=None):
    expires = str(int(time.time()) + 20 if expires is None else expires)
    payload = "\n".join(("supportflow-ai:v1", caller, expires, method, path, tenant))
    digest = hmac.digest((key or KEYS[caller]).encode(), payload.encode(), "sha256").hex()
    return {"X-SupportFlow-AI-Auth": f"{caller}:{expires}:{digest}", "X-Tenant-Code": tenant}


def test_only_public_health_is_reachable_without_a_service_identity(client):
    assert client.get("/api/ai/health").status_code == 200
    trace_only = {"X-Request-Id": "request-trace", "X-Tenant-Code": "TENANT_A"}
    assert client.post(BRAIN, json={}, headers=trace_only).status_code == 401
    assert client.post(KB + "/index", headers=trace_only).status_code == 401
    assert client.delete(KB + "/documents/7", headers=trace_only).status_code == 401
    assert client.get("/api/ai/v1/bot/training", headers=trace_only).status_code == 401
    assert client.get("/api/internal/usage/metrics", headers=trace_only).status_code == 401


def test_wrong_key_expiry_method_and_path_are_rejected(client):
    path = KB + "/documents/7"
    assert client.delete(path, headers=signed("DELETE", path, key=KEYS["gateway"])).status_code == 401
    assert client.delete(path, headers=signed("DELETE", path, expires=int(time.time()) - 2)).status_code == 401
    assert client.delete(path, headers=signed("GET", path)).status_code == 401
    assert client.delete(path, headers=signed("DELETE", KB + "/documents/8")).status_code == 401


def test_roles_are_scoped_to_service_operations(client):
    path = KB + "/documents/7"
    assert client.delete(path, headers=signed("DELETE", path, caller="gateway")).status_code == 403
    assert client.delete(path, headers=signed("DELETE", path, caller="ops")).status_code == 403
    path = "/api/internal/usage/tenants"
    assert client.get(path, headers=signed("GET", path, caller="customer")).status_code == 403
    path = "/api/internal/usage/trace/test-id"
    assert client.get(path, headers=signed("GET", path, caller="tenant", tenant="")).status_code == 403


def test_vector_repair_diagnostics_require_customer_role_and_a_tenant(client, monkeypatch):
    path = KB + "/repair-status"
    monkeypatch.setattr(kb, "_vector_quality", lambda tenant, **kw: {"tenant": tenant, **kw})
    assert client.get(path, headers=signed("GET", path, caller="gateway")).status_code == 403
    assert client.get(path, headers=signed("GET", path, tenant="")).status_code == 403
    response = client.get(path, headers=signed("GET", path, tenant="TENANT_B"))
    assert response.json() == {"tenant": "TENANT_B", "include_document_ids": True}


def test_conflicting_tenants_cannot_reach_index_search_brain_or_delete(client, monkeypatch):
    def unexpected(*_args, **_kwargs):
        raise AssertionError("Unauthorized requests must not reach business logic")

    monkeypatch.setattr(kb.store, "delete_chunks", unexpected)
    monkeypatch.setattr(kb.pipeline, "index_text", unexpected)
    monkeypatch.setattr(kb.pipeline, "search", unexpected)
    monkeypatch.setattr(brain.brain_agent, "think", unexpected)
    path = KB + "/documents/7"
    assert client.delete(path + "?tenant_code=TENANT_B", headers=signed("DELETE", path)).status_code == 403
    assert client.delete(path + "?tenant_code=TENANT_B&tenant_code=TENANT_A",
                         headers=signed("DELETE", path)).status_code == 403
    path = KB + "/index-text"
    assert client.post(path + "?tenant_code=TENANT_B", json={"docId": 7, "content": "hello"},
                       headers=signed("POST", path)).status_code == 403
    path = KB + "/search"
    assert client.post(path, json={"tenantCode": "TENANT_B", "query": "hello"},
                       headers=signed("POST", path)).status_code == 403
    # FastAPI accepts JSON without an explicit content type too.
    assert client.post(BRAIN, content=b'{"tenant_code":"TENANT_B","messages":[]}',
                       headers=signed("POST", BRAIN)).status_code == 403
    path = KB + "/documents/7"
    assert client.delete(path + "?tenant_code=TENANT_A", headers={
        **signed("DELETE", path), "X-Tenant-Code": "TENANT_B",
    }).status_code == 401


def test_signed_customer_can_index_delete_and_handle_a_conversation(client, monkeypatch):
    observed = []

    def index(**kwargs):
        observed.append(("index", kwargs["tenant_code"], kwargs["doc_id"], kwargs["content"]))
        return kb.pipeline.IndexResult(7, "test.txt", 5, 1, 2, "test-embedding", "stub")

    async def think(**kwargs):
        observed.append(("brain", kwargs["tenant_code"], kwargs["question"]))
        return {"reply": "test reply", "intent": "hello"}

    def delete(tenant, doc):
        observed.append(("delete", tenant, doc))
        return 1

    monkeypatch.setattr(kb.pipeline, "index_text", index)
    monkeypatch.setattr(brain.brain_agent, "think", think)
    monkeypatch.setattr(kb.store, "delete_chunks", delete)
    path = KB + "/index-text"
    response = client.post(path + "?tenant_code=TENANT_A", json={"docId": 7, "content": "hello"},
                           headers=signed("POST", path))
    assert response.status_code == 200, response.text
    assert response.json()["chunkCount"] == 1
    response = client.post(BRAIN, json={"tenant_code": "TENANT_A",
                                      "messages": [{"role": "user", "content": "hello"}]},
                           headers=signed("POST", BRAIN))
    assert response.status_code == 200, response.text
    assert response.json()["reply"] == "test reply"
    path = KB + "/documents/7"
    response = client.delete(path + "?tenant_code=TENANT_A", headers=signed("DELETE", path))
    assert response.status_code == 200, response.text
    assert observed == [("index", "TENANT_A", 7, "hello"), ("brain", "TENANT_A", "hello"), ("delete", "TENANT_A", 7)]


def test_processing_errors_do_not_expose_internal_exception_text(client, monkeypatch):
    def unavailable(**_kwargs):
        raise RuntimeError("PRIVATE UPSTREAM DETAILS")

    monkeypatch.setattr(kb.pipeline, "index_text", unavailable)
    path = KB + "/index-text"
    response = client.post(path + "?tenant_code=TENANT_A", json={"docId": 7, "content": "hello"},
                           headers=signed("POST", path))
    assert response.status_code == 500
    assert "PRIVATE UPSTREAM DETAILS" not in response.text


def test_speech_requests_must_keep_the_signed_tenant_for_usage_attribution(client):
    path = "/api/ai/v1/speech/tts"
    assert client.post(path, json={"text": "hello"}, headers=signed("POST", path)).status_code == 422
    assert client.post(path, json={"text": "hello", "tenant_code": "TENANT_B"},
                       headers=signed("POST", path)).status_code == 403
