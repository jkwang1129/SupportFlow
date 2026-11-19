"""Unit/API tests must not load a developer's credentials or mutate their AI database."""

import os

import ai.config
from ai.config import Settings

# Replace the factory before API modules are imported during collection.
def _test_settings():
    return Settings(
        _env_file=None,
        enforce_runtime_db_roles=False,
        database_url="postgresql://test@127.0.0.1:1/ai_db?connect_timeout=1",
        kb_database_url=os.getenv("SUPPORTFLOW_KB_TEST_DSN", "postgresql://test@127.0.0.1:1/customer_db?connect_timeout=1"),
        qwen_api_key="",
        deepseek_api_key="",
        embedding_api_key="",
        speech_access_key_secret="",
        gateway_secret="unit-gateway-000000000000000000000000000",
        customer_secret="unit-customer-00000000000000000000000000",
        ops_secret="unit-ops-0000000000000000000000000000000",
        tenant_secret="unit-tenant-0000000000000000000000000000",
    )


ai.config.get_settings = _test_settings
