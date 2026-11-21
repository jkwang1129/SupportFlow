"""Synthetic wire fixtures shared by unit and disposable-PG receiver tests."""
import hashlib
import hmac
import json
import time
from uuid import uuid4

from ai import main
from ai.api import runtime
from ai.config import Settings

KEYS = {role: 'runtime-test-only-' + role + '-0000000000000000000000000'
        for role in ('customer', 'gateway', 'ops', 'tenant')}
PATH = '/api/ai/v1/runtime/runs'


def event(**changes):
    return {**dict(tenant_code='TENANT_A', source_event_id=str(uuid4()), session_no='S1', event_seq=1,
                   message_id=42, principal_ref='visitor-session:7', trace_id='trace-1',
                   runtime_version='runtime-v1'), **changes}


def encode(value):
    return json.dumps(value, separators=(',', ':'), ensure_ascii=False).encode()


def signed(method, path=PATH, body=b'', *, tenant='TENANT_A', caller='customer', expires=None, key=None):
    expires = str(int(time.time()) + 20 if expires is None else expires)
    raw_path, _, query = path.partition('?')
    payload = '\n'.join(('supportflow-ai-runtime:v1', caller, expires, method, raw_path,
                         query, tenant, hashlib.sha256(body).hexdigest()))
    digest = hmac.digest((key or KEYS[caller]).encode(), payload.encode(), 'sha256').hex()
    return {'X-SupportFlow-AI-Auth': f'runtime:{caller}:{expires}:{digest}',
            'X-Tenant-Code': tenant, 'Content-Type': 'application/json'}


def build_app(monkeypatch, *, store=None, enabled=True, ready=True,ticket_enabled=None):
    ticket_setting={} if ticket_enabled is None else dict(runtime_ticket_actions_enabled=ticket_enabled)
    settings = Settings(_env_file=None, runtime_api_enabled=enabled, enforce_runtime_db_roles=False,
                        **ticket_setting,
                        **{f'{caller}_secret': key for caller, key in KEYS.items()})
    monkeypatch.setattr(main, 'get_settings', lambda: settings)
    monkeypatch.setattr(main, 'ensure_schema', lambda: None)
    monkeypatch.setattr(runtime, 'get_settings', lambda: settings)
    if ready:
        monkeypatch.setattr(main.schema_guard, 'is_ready', lambda: True)
    app = main.create_app()
    if store is not None:
        app.dependency_overrides[runtime.runtime_store] = lambda: store
    return app
