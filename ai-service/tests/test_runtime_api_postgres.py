"""Real HTTP admission and fault injection with the actual restricted AI identity."""
from concurrent.futures import ThreadPoolExecutor
import os
from threading import Barrier
from uuid import UUID

from fastapi.testclient import TestClient
import psycopg
from psycopg.rows import dict_row
import pytest

from ai.core import schema_guard
from ai.runtime.store import RuntimeStore
from ai.runtime.types import Lease
from runtime_api_support import PATH, build_app, encode, event, signed

pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),
                                reason='requires disposable runtime PostgreSQL')


def admin():
    return psycopg.connect(os.environ['SUPPORTFLOW_RUNTIME_ADMIN_TEST_DSN'], autocommit=True, row_factory=dict_row)


@pytest.fixture
def receiver(monkeypatch):
    with admin() as connection:
        connection.execute('TRUNCATE public.agent_approval, public.agent_ticket_reconciliation, public.agent_reply_reconciliation, public.agent_model_usage, public.agent_model_quota, public.agent_payload, public.agent_step, public.agent_run')
    def connect(**kwargs):
        return psycopg.connect(os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'], row_factory=dict_row, **kwargs)
    store = RuntimeStore(connect)
    monkeypatch.setattr(schema_guard, 'connect', connect)
    # This fixture isolates the additional runtime readiness checks; full AI/KB
    # checks are exercised by test-runtime-security.py, with separate databases.
    monkeypatch.setattr(schema_guard, '_missing', schema_guard.runtime_missing)
    app = build_app(monkeypatch, store=store, ready=False)
    with TestClient(app) as client:
        yield client, store


def post(client, value):
    body = encode(value)
    return client.post(PATH, content=body, headers=signed('POST', body=body))


def test_duplicate_delivery_is_one_run_and_does_not_refresh_budget_or_identity(receiver):
    client, store = receiver
    value = event()
    first = post(client, value)
    assert first.status_code == 202 and first.json()['state'] == 'PENDING'
    assert post(client, value).json() == first.json()
    for changed in ({'principal_ref': 'visitor-session:8'}, {'message_id': 99},
                    {'trace_id': 'another-trace'}, {'session_no': 'another-session'}):
        assert post(client, {**value, **changed}).status_code == 409
    row = store.get('TENANT_A', UUID(first.json()['run_id']))
    assert (row['max_steps'], row['max_model_calls'], row['max_tool_calls'], row['max_retries']) == (12, 4, 8, 2)
    assert (row['attempt'], row['step_count'], row['model_calls']) == (0, 0, 0)
    with admin() as connection:
        assert connection.execute('SELECT count(*) AS n FROM agent_run').fetchone()['n'] == 1


def test_eight_concurrent_duplicate_http_deliveries_create_one_run(receiver):
    client, store = receiver
    value = event()
    barrier = Barrier(8)
    def submit(_):
        barrier.wait(timeout=10)
        response = post(client, value)
        assert response.status_code == 202, response.text
        return response.json()['run_id']
    with ThreadPoolExecutor(8) as pool:
        assert len(set(pool.map(submit, range(8)))) == 1
    with admin() as connection:
        assert connection.execute('SELECT count(*) AS n FROM agent_run').fetchone()['n'] == 1


def test_cross_tenant_status_cancel_and_pending_cancellation(receiver):
    client, store = receiver
    value = event()
    run = post(client, value).json()
    path = PATH + '/' + run['run_id']
    for tenant in ('TENANT_B',):
        assert client.get(path, headers=signed('GET', path, tenant=tenant)).status_code == 404
        assert client.post(path + '/cancel', content=b'{}', headers=signed('POST', path + '/cancel', b'{}', tenant=tenant)).status_code == 404
    assert client.get(path, headers=signed('GET', path)).json()['state'] == 'PENDING'
    cancel = client.post(path + '/cancel', content=b'{}', headers=signed('POST', path + '/cancel', b'{}'))
    assert cancel.status_code == 200 and cancel.json()['state'] == 'CANCELLED'
    assert store.claim_next('TENANT_A', 'worker') is None
    # Lost delivery response must retain terminal state, not create new work.
    assert post(client, value).json()['state'] == 'CANCELLED'


def test_unknown_write_cancellation_is_held_as_failed_not_claimed_undone(receiver):
    client, store = receiver
    run = post(client, event()).json()
    lease = Lease.from_run(store.claim_next('TENANT_A', 'worker'))
    store.reserve_step(lease, 'write:1', 'TOOL', 'WRITE', {'operation': 'create-ticket'})
    path = PATH + '/' + run['run_id'] + '/cancel'
    result = client.post(path, content=b'{}', headers=signed('POST', path, b'{}'))
    assert result.status_code == 200
    assert result.json()['state'] == 'FAILED' and result.json()['error_code'] == 'SIDE_EFFECT_UNKNOWN'
    assert 'operation' not in result.text


def test_backlog_rejects_new_work_but_allows_retry_of_same_event(receiver, monkeypatch):
    client, store = receiver
    monkeypatch.setattr(store, 'backlog_limit', 1)
    first = event()
    assert post(client, first).status_code == 202
    rejected = post(client, event(session_no='S2'))
    assert rejected.status_code == 429 and rejected.headers['Retry-After'] == '1'
    assert post(client, first).status_code == 202
    assert post(client, event(tenant_code='TENANT_B')).status_code == 403  # body/signature mismatch


@pytest.mark.parametrize('fault,repair', [
    ('ALTER TABLE agent_payload RENAME COLUMN payload_hash TO hidden_hash','ALTER TABLE agent_payload RENAME COLUMN hidden_hash TO payload_hash'),
    ('ALTER TABLE agent_payload NO FORCE ROW LEVEL SECURITY','ALTER TABLE agent_payload FORCE ROW LEVEL SECURITY'),
    ('GRANT UPDATE ON agent_payload TO supportflow_ai','REVOKE UPDATE ON agent_payload FROM supportflow_ai'),
    ('REVOKE INSERT ON agent_payload FROM supportflow_agent_runtime,supportflow_ai','GRANT INSERT ON agent_payload TO supportflow_agent_runtime,supportflow_ai'),
    ('REVOKE SELECT ON agent_payload FROM supportflow_agent_runtime,supportflow_ai','GRANT SELECT ON agent_payload TO supportflow_agent_runtime,supportflow_ai'),
    ('ALTER TABLE agent_step RENAME COLUMN intent_hash TO hidden_intent_hash',
     'ALTER TABLE agent_step RENAME COLUMN hidden_intent_hash TO intent_hash'),
    ('ALTER TABLE agent_run RENAME TO hidden_agent_run', 'ALTER TABLE hidden_agent_run RENAME TO agent_run'),
    ('REVOKE INSERT ON agent_run FROM supportflow_agent_runtime,supportflow_ai', 'GRANT INSERT ON agent_run TO supportflow_agent_runtime,supportflow_ai'),
    ('REVOKE SELECT ON agent_step FROM supportflow_agent_runtime,supportflow_ai', 'GRANT SELECT ON agent_step TO supportflow_agent_runtime,supportflow_ai'),
    ('REVOKE UPDATE ON agent_step FROM supportflow_agent_runtime,supportflow_ai', 'GRANT UPDATE ON agent_step TO supportflow_agent_runtime,supportflow_ai'),
    ('REVOKE supportflow_agent_runtime FROM supportflow_ai', 'GRANT supportflow_agent_runtime TO supportflow_ai'),
    ('ALTER ROLE supportflow_ai BYPASSRLS', 'ALTER ROLE supportflow_ai NOBYPASSRLS'),
    ('GRANT DELETE ON agent_run TO supportflow_ai', 'REVOKE DELETE ON agent_run FROM supportflow_ai'),
    ('REVOKE EXECUTE ON FUNCTION supportflow_agent_due_tenants(integer) FROM supportflow_agent_runtime',
     'GRANT EXECUTE ON FUNCTION supportflow_agent_due_tenants(integer) TO supportflow_agent_runtime'),
    ('ALTER TABLE agent_step NO FORCE ROW LEVEL SECURITY', 'ALTER TABLE agent_step FORCE ROW LEVEL SECURITY'),
])
def test_runtime_schema_permission_faults_close_ready_and_admission_without_restart(receiver, fault, repair):
    client, _ = receiver
    assert client.get('/api/ai/health/ready').status_code == 200
    with admin() as connection:
        connection.execute(fault)
        try:
            ready = client.get('/api/ai/health/ready')
            assert ready.status_code == 503 and 'agent_' not in ready.text
            assert client.get('/api/ai/health/live').status_code == 200
            response = post(client, event())
            assert response.status_code == 503 and 'agent_' not in response.text
            # Authentication still precedes the dependency gate.
            assert client.post(PATH, content=b'{}').status_code == 401
        finally:
            connection.execute(repair)
    assert client.get('/api/ai/health/ready').status_code == 200
    assert post(client, event()).status_code == 202
