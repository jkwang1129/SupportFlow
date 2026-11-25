"""Resolve live bot settings through the restricted AI application identity."""

import os
from uuid import uuid4

import psycopg
from psycopg.rows import dict_row
import pytest

from ai.config import Settings
from ai.core import llm_chat


@pytest.mark.skipif(not os.getenv("SUPPORTFLOW_RUNTIME_TEST_DSN"), reason="requires disposable runtime PostgreSQL")
def test_chat_routing_respects_tenants_and_current_model_rows(monkeypatch):
    def connect(**options):
        return psycopg.connect(os.environ["SUPPORTFLOW_RUNTIME_TEST_DSN"], row_factory=dict_row, **options)

    suffix = uuid4().hex[:8]
    tenant_a, tenant_b = "RA_" + suffix, "RB_" + suffix
    model_id = int(uuid4().hex[:14], 16)
    configured = Settings(_env_file=None, qwen_api_key="fixture-qwen-key", deepseek_api_key="fixture-deepseek-key",
                          llm_default_provider="qwen", llm_default_model="")
    monkeypatch.setattr(llm_chat, "connect", connect)
    monkeypatch.setattr(llm_chat, "get_settings", lambda: configured)
    try:
        with connect() as db:
            db.execute("INSERT INTO bot_setting (id, tenant_code, model_key) VALUES (%s,%s,%s),(%s,%s,%s)",
                       (model_id, tenant_a, "shared-model", model_id + 1, tenant_b, "shared-model"))
            db.execute("""
                INSERT INTO bot_model (id,tenant_code,model_key,model_name,provider,model_type)
                VALUES (%s,%s,'shared-model','fixture','deepseek',1),
                       (%s,%s,'shared-model','fixture','qwen',1)
            """, (model_id, tenant_a, model_id + 1, tenant_b))
        assert llm_chat.resolve_provider(tenant_a)[0]["provider"] == "deepseek"
        assert llm_chat.resolve_provider(tenant_b)[0]["provider"] == "qwen"
        with connect() as db:
            db.execute("UPDATE bot_model SET is_enabled=FALSE WHERE tenant_code=%s", (tenant_a,))
        assert llm_chat.resolve_provider(tenant_a) == (None, "MODEL_SELECTION_INVALID")
        assert llm_chat.resolve_provider(tenant_b)[0]["provider"] == "qwen"
        with connect() as db:
            db.execute("UPDATE bot_model SET is_deleted=TRUE WHERE tenant_code=%s", (tenant_a,))
        assert llm_chat.resolve_provider(tenant_a) == (None, "MODEL_SELECTION_INVALID")
        with connect() as db:
            db.execute("UPDATE bot_setting SET model_key=NULL WHERE tenant_code=%s", (tenant_a,))
        assert llm_chat.resolve_provider(tenant_a)[0]["provider"] == "qwen"
    finally:
        with connect() as db:
            db.execute("DELETE FROM bot_model WHERE tenant_code IN (%s,%s)", (tenant_a, tenant_b))
            db.execute("DELETE FROM bot_setting WHERE tenant_code IN (%s,%s)", (tenant_a, tenant_b))
