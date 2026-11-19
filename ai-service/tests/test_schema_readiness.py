"""Readiness is separate from liveness; it cannot execute migrations or leak DSNs."""
import hmac
import time
from unittest.mock import MagicMock

import pytest
from fastapi.testclient import TestClient

from ai import main
from ai.api import brain
from ai.config import get_settings
from ai.core import schema_guard


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(main, "ensure_schema", lambda: None)
    with TestClient(main.create_app()) as instance:
        yield instance


def test_legacy_auto_migrate_flag_cannot_run_ddl(monkeypatch, tmp_path):
    script = tmp_path / "legacy.sql"
    script.write_text("CREATE TABLE should_never_run(id int)", encoding="utf-8")
    connection = MagicMock()
    monkeypatch.setenv("SUPPORTFLOW_AI_AUTO_MIGRATE", "true")
    monkeypatch.setattr(schema_guard, "_missing", lambda: {"bot_setting.brand_name": "legacy.sql"})
    monkeypatch.setattr(schema_guard, "_script_path", lambda _: script, raising=False)
    monkeypatch.setattr(schema_guard, "connect", connection)
    schema_guard.ensure_schema()
    connection.assert_not_called()


def test_missing_schema_does_not_pass_ready_but_liveness_still_works(client, monkeypatch):
    monkeypatch.setattr(schema_guard, "_missing", lambda: {"ai_db.bot_setting.brand_name": "baseline"})
    assert client.get("/api/ai/health").status_code == 200
    assert client.get("/api/ai/health/live").status_code == 200
    assert client.get("/api/ai/health/ready").status_code == 503
    monkeypatch.setattr(schema_guard, "_missing", lambda: {})
    assert client.get("/api/ai/health/ready").status_code == 200


def test_connection_error_is_not_ready_and_never_returns_the_dsn(client, monkeypatch):
    def failed():
        raise RuntimeError("secret-dsn-do-not-return")
    monkeypatch.setattr(schema_guard, "_missing", failed)
    response = client.get("/api/ai/health/ready")
    assert response.status_code == 503
    assert "secret-dsn-do-not-return" not in response.text
    assert "secret-dsn-do-not-return" not in schema_guard.describe()


def test_authenticated_business_request_is_blocked_before_the_model_when_not_ready(client, monkeypatch):
    monkeypatch.setattr(schema_guard, "_missing", lambda: {"ai_db.bot_dialogue": "baseline"})
    calls = []
    async def model(**kwargs):
        calls.append(kwargs)
        return {"reply": "synthetic"}
    monkeypatch.setattr(brain.brain_agent, "think", model)
    path = "/api/ai/v1/agent/brain"
    expiry = str(int(time.time()) + 20)
    canonical = "\n".join(("supportflow-ai:v1", "customer", expiry, "POST", path, "TENANT_A"))
    signature = hmac.digest(get_settings().customer_secret.get_secret_value().encode(), canonical.encode(), "sha256").hex()
    response = client.post(path, headers={"X-SupportFlow-AI-Auth": f"customer:{expiry}:{signature}",
                                        "X-Tenant-Code": "TENANT_A"}, json={"tenant_code": "TENANT_A",
                                        "messages": [{"role": "user", "content": "synthetic"}]})
    assert response.status_code == 503
    assert calls == []
    assert client.post(path, json={}).status_code == 401
