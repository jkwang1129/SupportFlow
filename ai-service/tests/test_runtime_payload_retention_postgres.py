"""Qualify preview, bounded deletion and audit preservation on disposable PostgreSQL."""
import json
import os
from pathlib import Path
import subprocess
import sys

import httpx
import psycopg
import pytest

from ai.runtime.models import RuntimeChatClient
from ai.runtime.payload_retention import MAINTENANCE_DSN, process_batch
from ai.runtime.types import Lease
from ai.runtime.worker import RuntimeWorker
from test_chat_transport import response
from test_runtime_models import MESSAGES, answer, profile
from test_runtime_payloads_postgres import VALUE
from test_runtime_store_postgres import admin, request, store


pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RETENTION_TEST_DSN'), reason='requires disposable maintenance role')
FUNCTION = 'SELECT public.supportflow_expired_payload_batch(%s,%s,%s)'


def batch(tenant='TENANT_A', limit=100, *, apply=False):
    return process_batch(os.environ['SUPPORTFLOW_RETENTION_TEST_DSN'], tenant, limit, apply=apply)


def checkpoint(store, session, *, tenant='TENANT_A', expired=True, terminal=True):
    row = store.enqueue(request(tenant, session))
    lease = Lease.from_run(store.claim_next(tenant, 'retention-fixture'))
    assert row['run_id'] == lease.run_id
    store.reserve_step(lease, 'body', 'MODEL', 'NONE', {'operation': 'fixture', 'version': 'retention-v1'})
    store.complete_payload_step(lease, 'body', 'model-output-v1', VALUE)
    if terminal:
        store.finish(lease, {'outcome': 'fixture'})
    if expired:
        expire_body(lease.run_id)
    return lease.run_id


def expire_body(run):
    with admin() as connection:
        connection.execute("""UPDATE agent_payload SET created_at=statement_timestamp()-interval '8 days',
                              expires_at=statement_timestamp()-interval '1 day' WHERE run_id=%s""", (run,))


def remaining():
    with admin() as connection:
        return {row['run_id'] for row in connection.execute('SELECT run_id FROM agent_payload')}


def test_preserves_fresh_active_approval_unknown_and_foreign_payloads(store):
    expired = checkpoint(store, 'expired')
    fresh = checkpoint(store, 'fresh', expired=False)
    active = checkpoint(store, 'active', terminal=False)
    approval = checkpoint(store, 'approval', terminal=False)
    unknown = checkpoint(store, 'unknown')
    foreign = checkpoint(store, 'foreign', tenant='TENANT_B')
    with admin() as connection:
        connection.execute("UPDATE agent_run SET state='WAITING_APPROVAL',owner=NULL,lease_until=NULL WHERE run_id=%s", (approval,))
        connection.execute("UPDATE agent_run SET state='FAILED',error_code='SIDE_EFFECT_UNKNOWN' WHERE run_id=%s", (unknown,))
    assert batch()['selected'] == 1 and remaining() == {expired, fresh, active, approval, unknown, foreign}
    assert batch(apply=True)['deleted'] == 1
    assert batch(apply=True)['deleted'] == 0
    assert batch('TENANT_B')['selected'] == 1
    assert remaining() == {fresh, active, approval, unknown, foreign}


def test_batch_limit_and_active_locks_do_not_double_delete(store):
    locked = checkpoint(store, 'locked')
    first = checkpoint(store, 'first')
    second = checkpoint(store, 'second')
    with admin() as connection, connection.transaction():
        connection.execute('SELECT 1 FROM agent_run WHERE run_id=%s FOR UPDATE', (locked,))
        assert batch(limit=1)['selected'] == 1
        assert batch(limit=1, apply=True)['deleted'] == 1
        assert remaining() == {locked, second} and first not in remaining()
        assert batch(limit=1, apply=True)['deleted'] == 1
        assert batch(limit=1, apply=True)['deleted'] == 0
    assert batch(apply=True)['deleted'] == 1 and remaining() == set()


def test_read_only_and_transaction_rollback_preserve_expired_payload(store):
    run = checkpoint(store, 'rollback')
    with psycopg.connect(os.environ['SUPPORTFLOW_RETENTION_TEST_DSN'], autocommit=True) as connection:
        with pytest.raises(psycopg.errors.ReadOnlySqlTransaction), connection.transaction():
            connection.execute('SET TRANSACTION READ ONLY')
            connection.execute(FUNCTION, ('TENANT_A', 1, True))
        with connection.transaction(force_rollback=True):
            assert connection.execute(FUNCTION, ('TENANT_A', 1, True)).fetchone() == (1,)
    assert remaining() == {run}


def test_maintenance_has_function_only_and_runtime_cannot_call_it(store):
    with psycopg.connect(os.environ['SUPPORTFLOW_RETENTION_TEST_DSN'], autocommit=True) as connection:
        for attempt in ('SELECT * FROM public.agent_payload', 'DELETE FROM public.agent_payload',
                        'SELECT * FROM public.agent_run', 'TRUNCATE public.agent_payload'):
            with pytest.raises(psycopg.errors.InsufficientPrivilege):
                connection.execute(attempt)
        for scope in ((None, 1, False), ('PLATFORM', 1, False), ('TENANT_A', 501, True), ('TENANT_A', 1, None)):
            with pytest.raises(psycopg.errors.InvalidParameterValue):
                connection.execute(FUNCTION, scope)
    with psycopg.connect(os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN']) as connection:
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            connection.execute(FUNCTION, ('TENANT_A', 1, False))


def test_cli_retains_trace_events_and_model_usage_after_purge(store, tmp_path):
    tenant = 'TENANT_A'
    run = store.enqueue(request())['run_id']
    client = RuntimeChatClient(profile(), transport=httpx.MockTransport(lambda _: response(answer())))

    def handler(context):
        client.complete(context, 'plan', MESSAGES)
        return {'outcome': 'retention-fixture'}

    worker = RuntimeWorker(store, {'runtime-v1': handler}, 'retention-fixture')
    assert worker.run_once(tenant)['state'] == 'SUCCEEDED'
    expire_body(run)
    before_trace, before_usage = store.trace_snapshot(tenant, run), store.model_usage(tenant, run)
    with admin() as connection:
        before_events = connection.execute('SELECT * FROM agent_run_event ORDER BY seq').fetchall()
    env = {key: os.environ[key] for key in ('PATH', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP') if key in os.environ}
    env.update(PYTHONUTF8='1', PYTHONPATH=str(Path(__file__).resolve().parents[1]))
    env[MAINTENANCE_DSN] = os.environ['SUPPORTFLOW_RETENTION_TEST_DSN']

    def cli(*flags):
        result = subprocess.run([sys.executable, '-m', 'ai.runtime.payload_retention', '--tenant', tenant, *flags],
                                cwd=tmp_path, env=env, capture_output=True, text=True, encoding='utf-8', timeout=10)
        assert result.returncode == 0 and not result.stderr and str(run) not in result.stdout
        return json.loads(result.stdout)

    preview, applied, repeated = cli(), cli('--apply'), cli('--apply')
    assert (preview['selected'], preview['deleted'], applied['deleted'], repeated['deleted']) == (1, 0, 1, 0)
    assert store.trace_snapshot(tenant, run) == before_trace
    assert store.model_usage(tenant, run) == before_usage and before_usage['observed_input_tokens'] == 13
    with admin() as connection:
        assert connection.execute('SELECT * FROM agent_run_event ORDER BY seq').fetchall() == before_events
    if path := os.getenv('SUPPORTFLOW_PAYLOAD_RETENTION_TEST_REPORT'):
        Path(path).write_text(json.dumps({'preview': preview, 'applied': applied, 'repeated': repeated,
                                         'trace_unchanged': True, 'events_preserved': len(before_events),
                                         'observed_input_tokens': 13, 'model_usage_unchanged': True}, indent=2)+'\n', encoding='utf-8')
