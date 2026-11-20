"""Actual AI app with disposable credentials; never loads developer configuration."""
import os
import sys

import ai.config
from ai.config import Settings

settings = Settings(
    _env_file=None, runtime_api_enabled=True, enforce_runtime_db_roles=True,
    database_url=os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'],
    kb_database_url=os.environ['SUPPORTFLOW_DELIVERY_TEST_KB_DSN'],
    customer_secret=os.environ['SUPPORTFLOW_RUNTIME_CUSTOMER_TEST_SECRET'],
    gateway_secret='delivery-fixture-gateway-0000000000000000',
    ops_secret='delivery-fixture-ops-00000000000000000000',
    tenant_secret='delivery-fixture-tenant-00000000000000000',
    llm_default_provider='mock', qwen_api_key='', deepseek_api_key='', embedding_api_key='',
    speech_enabled=False, speech_access_key_secret='', llm_log_payload=False,
)
ai.config.get_settings = lambda: settings

if __name__ == '__main__':
    import uvicorn
    from ai.main import app
    uvicorn.run(app, host='127.0.0.1', port=int(sys.argv[1]), access_log=False, log_level='warning')
