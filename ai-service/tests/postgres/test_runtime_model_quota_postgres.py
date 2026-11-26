"""Model admission uses the same tenant quota across PostgreSQL sessions."""
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
import json
import os
from pathlib import Path
import subprocess
import sys
from threading import Barrier, Event

import httpx
import psycopg
from psycopg.rows import dict_row
import pytest

from ai.core import schema_guard
from ai.runtime.models import RuntimeChatClient
from ai.runtime.store import RuntimeStore
from ai.runtime.types import Lease, Limits, LostLease
from ai.runtime.worker import RuntimeWorker
from test_chat_transport import response
from test_runtime_models import MESSAGES, answer, profile
from test_runtime_store_postgres import acquire, admin, expire, request, store


pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),
                                reason='requires disposable runtime PostgreSQL')


def _quota(tenant, limit):
    with admin() as connection:
        connection.execute('''INSERT INTO agent_model_quota(tenant_code,max_active_steps) VALUES(%s,%s)
            ON CONFLICT (tenant_code) DO UPDATE SET max_active_steps=EXCLUDED.max_active_steps''', (tenant, limit))


def _ready(run_id):
    with admin() as connection:
        connection.execute('UPDATE agent_run SET ready_at=clock_timestamp() WHERE run_id=%s', (run_id,))


def _run_model(context):
    return context.step('model', 'MODEL', 'NONE', {'operation': 'fixture.model'}, lambda: {'outcome': 'done'})


def test_tenant_model_quota_serializes_workers_and_preserves_retries(store):
    _quota('TENANT_A', 1)
    _quota('TENANT_B', 1)
    limits = Limits(max_model_calls=1, max_retries=0)
    first = store.enqueue(request(session='A1'), limits)
    second = store.enqueue(request(session='A2'), limits)
    other = store.enqueue(request('TENANT_B'), limits)
    barrier, entered, release = Barrier(2), Event(), Event()
    calls = []

    def make_worker(tenant, owner, *, competing=False):
        def provider(_request):
            calls.append(tenant)
            if competing:
                entered.set()
                assert release.wait(12)
            return response(answer())

        client = RuntimeChatClient(profile(tenant=tenant), transport=httpx.MockTransport(provider))

        def handler(context):
            if competing:
                barrier.wait(8)
            client.complete(context, 'model', MESSAGES)
            return {'outcome': 'done'}

        return RuntimeWorker(RuntimeStore(store.connector), {'runtime-v1': handler}, owner, tenant=tenant)

    with ThreadPoolExecutor(2) as executor:
        futures = [executor.submit(make_worker('TENANT_A', name, competing=True).run_once, 'TENANT_A')
                   for name in ('competing-a1', 'competing-a2')]
        try:
            assert entered.wait(8)
            done, pending = wait(futures, timeout=8, return_when=FIRST_COMPLETED)
            assert len(done) == len(pending) == 1
            deferred = done.pop().result()
            assert deferred['run_id'] in (first['run_id'], second['run_id'])
            assert (deferred['state'], deferred['error_code']) == ('RETRY_WAIT', 'MODEL_CAPACITY_BUSY')
            assert (deferred['model_calls'], deferred['step_count'], deferred['retry_count']) == (0, 0, 0)
            original = first if deferred['run_id'] == first['run_id'] else second
            assert deferred['deadline_at'] == original['deadline_at']
            assert deferred['ready_at'] <= deferred['deadline_at']
            assert store.steps('TENANT_A', deferred['run_id']) == [] and calls == ['TENANT_A']
            independent = make_worker('TENANT_B', 'independent-b').run_once('TENANT_B')
            assert independent['run_id'] == other['run_id'] and independent['state'] == 'SUCCEEDED'
            assert calls == ['TENANT_A', 'TENANT_B'] and not next(iter(pending)).done()
        finally:
            release.set()
        assert pending.pop().result(timeout=10)['state'] == 'SUCCEEDED'

    _ready(deferred['run_id'])
    resumed = make_worker('TENANT_A', 'resumed-a').run_once('TENANT_A')
    assert resumed['run_id'] == deferred['run_id'] and resumed['state'] == 'SUCCEEDED'
    assert (resumed['model_calls'], resumed['retry_count']) == (1, 0)
    assert calls == ['TENANT_A', 'TENANT_B', 'TENANT_A']
    with admin() as connection:
        assert connection.execute('SELECT count(*) AS n FROM agent_model_usage').fetchone()['n'] == 3
    artifact = Path(__file__).resolve().parents[3] / '.local/verification/runtime-model-concurrency.json'
    artifact.parent.mkdir(parents=True, exist_ok=True)
    artifact.write_text(json.dumps({'mode': 'DISPOSABLE_POSTGRES_17_SYNTHETIC_PROVIDER', 'tenant_limit': 1,
                                    'contending_workers': 2, 'deferred_model_calls': deferred['model_calls'],
                                    'deferred_retries': deferred['retry_count'], 'provider_calls': calls}, indent=2) + '\n', encoding='utf-8')


def test_worker_process_loss_keeps_capacity_reserved_until_fenced_takeover(store):
    _quota('TENANT_A', 1)
    original = store.enqueue(request(session='crashed'))
    another = store.enqueue(request(session='other'))
    program = """
import os
import psycopg
from ai.runtime.store import RuntimeStore
from ai.runtime.worker import RuntimeWorker

store = RuntimeStore(lambda: psycopg.connect(os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN']))
def crash(context):
    return context.step('model', 'MODEL', 'NONE', {'operation': 'fixture.model'}, lambda: os._exit(17))
RuntimeWorker(store, {'runtime-v1': crash}, 'quota-crash-worker').run_once('TENANT_A')
"""
    env = {key: os.environ[key] for key in ('PATH', 'SystemRoot', 'WINDIR',
                                            'TEMP', 'TMP', 'SUPPORTFLOW_RUNTIME_TEST_DSN') if key in os.environ}
    process = subprocess.run([sys.executable, '-c', program], cwd=Path(__file__).resolve().parents[2],
                             env=env, capture_output=True, timeout=15)
    assert process.returncode == 17
    held = store.get('TENANT_A', original['run_id'])
    assert [step['state'] for step in store.steps('TENANT_A', original['run_id'])] == ['RESERVED']
    runner = RuntimeWorker(store, {'runtime-v1': _run_model}, 'quota-recovery-worker')
    blocked = runner.run_once('TENANT_A')
    assert blocked['run_id'] == another['run_id'] and blocked['error_code'] == 'MODEL_CAPACITY_BUSY'
    assert blocked['model_calls'] == 0 and blocked['retry_count'] == 0

    old_lease = Lease.from_run(held)
    expire(old_lease)
    recovered = runner.run_once('TENANT_A')
    assert recovered['run_id'] == original['run_id'] and recovered['state'] == 'SUCCEEDED'
    assert (recovered['model_calls'], recovered['retry_count']) == (2, 1)
    assert [step['state'] for step in store.steps('TENANT_A', original['run_id'])] == ['UNKNOWN', 'SUCCEEDED']
    with pytest.raises(LostLease):
        store.complete_step(old_lease, 'model', 'SUCCEEDED', {'outcome': 'late'})
    _ready(another['run_id'])
    assert runner.run_once('TENANT_A')['state'] == 'SUCCEEDED'


def test_model_capacity_reopens_on_cancellation_and_paused_tasks_expire(store):
    _quota('TENANT_A', 1)
    cancelled = store.enqueue(request(session='cancelled'))
    lease = acquire(store)
    store.reserve_step(lease, 'model', 'MODEL', 'NONE', {})
    assert store.cancel('TENANT_A', cancelled['run_id'])['state'] == 'CANCELLED'
    with pytest.raises(LostLease):
        store.complete_step(lease, 'model', 'SUCCEEDED', {'outcome': 'late'})
    assert store.steps('TENANT_A', cancelled['run_id'])[0]['state'] == 'UNKNOWN'
    runner = RuntimeWorker(store, {'runtime-v1': _run_model}, 'quota-cancel-worker')
    queued = store.enqueue(request(session='next'))
    next_run = runner.run_once('TENANT_A')
    assert next_run['run_id'] == queued['run_id'] and next_run['state'] == 'SUCCEEDED'

    _quota('TENANT_A', 0)
    paused = store.enqueue(request(session='paused'), Limits(max_retries=0))
    deferred = runner.run_once('TENANT_A')
    assert (deferred['state'], deferred['error_code']) == ('RETRY_WAIT', 'MODEL_CAPACITY_BUSY')
    assert (deferred['model_calls'], deferred['retry_count']) == (0, 0)
    with admin() as connection:
        connection.execute('''UPDATE agent_run SET deadline_at=clock_timestamp()-interval '1 second',
            ready_at=clock_timestamp()-interval '1 second' WHERE run_id=%s''', (paused['run_id'],))
    assert runner.run_once('TENANT_A') is None
    final = store.get('TENANT_A', paused['run_id'])
    assert (final['state'], final['error_code'], final['model_calls'],
            final['retry_count']) == ('FAILED', 'DEADLINE_EXCEEDED', 0, 0)


def test_model_quota_operator_policy_is_tenant_scoped_and_worker_read_only(store, monkeypatch):
    _quota('TENANT_A', 2)
    _quota('TENANT_B', 1)
    for scope, expected in (('TENANT_A', [2]), ('TENANT_B', [1]), ('TENANT_C', [])):
        with store._scope(scope) as connection:
            values = connection.execute('SELECT max_active_steps FROM agent_model_quota').fetchall()
            assert [row['max_active_steps'] for row in values] == expected
    with store._scope('TENANT_A') as connection:
        for statement in ("INSERT INTO agent_model_quota VALUES ('TENANT_C',10)",
                          'UPDATE agent_model_quota SET max_active_steps=100',
                          'DELETE FROM agent_model_quota', 'TRUNCATE agent_model_quota'):
            with pytest.raises(psycopg.errors.InsufficientPrivilege), connection.transaction():
                connection.execute(statement)
    monkeypatch.setattr(schema_guard, 'connect', lambda **kwargs: psycopg.connect(
        os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'], row_factory=dict_row, **kwargs))
    assert schema_guard.runtime_missing() == {}
    with admin() as connection:
        connection.execute('GRANT UPDATE(max_active_steps) ON agent_model_quota TO supportflow_ai')
    try:
        assert schema_guard.runtime_missing(
        )['ai_db.agent_model_quota.runtime_permissions'] == schema_guard.MODEL_QUOTA_SOURCE
    finally:
        with admin() as connection:
            connection.execute('REVOKE UPDATE(max_active_steps) ON agent_model_quota FROM supportflow_ai')
    assert schema_guard.runtime_missing() == {}
