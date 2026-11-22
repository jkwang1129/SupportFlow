"""Durable worker faults and real process death, with no paid model or business effects."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import json
import os
from pathlib import Path
import subprocess
import sys
from threading import Barrier, Event
import time

import pytest

from ai.runtime.store import RuntimeStore
from ai.runtime.types import Limits, LostLease, QueueFull
from ai.runtime.worker import RuntimeWorker, TransientFailure, PermanentFailure
from test_runtime_store_postgres import store, request, admin, expire

pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'), reason='requires disposable runtime PostgreSQL')


def wait_until(predicate, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.05)
    raise AssertionError('Bounded fault-fixture condition did not become true')


def worker(store, handler, **kwargs):
    return RuntimeWorker(store, {'runtime-v1': handler}, 'worker-test', **kwargs)


def test_worker_runs_once_reuses_completed_step_and_persists_only_references(store):
    row = store.enqueue(request())
    calls = []

    def handler(context):
        result = context.step('lookup', 'TOOL', 'READ', {'operation': 'lookup'}, lambda: calls.append(1) or {'resource_ref': 'record-1'})
        assert context.step('lookup', 'TOOL', 'READ', {'operation': 'lookup'}, lambda: pytest.fail('replayed call')) == result
        return result

    executor = worker(store, handler)
    assert executor.run_once('TENANT_A')['state'] == 'SUCCEEDED'
    assert executor.run_once('TENANT_A') is None
    assert calls == [1]
    assert store.get('TENANT_A', row['run_id'])['tool_calls'] == 1


def test_transient_model_failure_retries_but_does_not_repeat_completed_step(store):
    row = store.enqueue(request())
    calls = []

    def handler(context):
        context.step('prepare', 'INTERNAL', 'NONE', {}, lambda: calls.append('prepare') or {'input_ref': 'prepared'})

        def model():
            calls.append('model')
            if calls.count('model') == 1:
                raise TransientFailure('MODEL_429', delay_seconds=0)
            return {'resource_ref': 'answer-1'}

        return context.step('answer', 'MODEL', 'NONE', {'operation': 'answer'}, model)

    executor = worker(store, handler)
    assert executor.run_once('TENANT_A')['state'] == 'RETRY_WAIT'
    assert executor.run_once('TENANT_A')['state'] == 'SUCCEEDED'
    result = store.get('TENANT_A', row['run_id'])
    assert (result['model_calls'], result['retry_count'], result['step_count']) == (2, 1, 3)
    assert calls == ['prepare', 'model', 'model']


def test_unknown_write_exception_never_retries_even_when_classified_transient(store):
    row = store.enqueue(request())
    calls = []

    def handler(context):
        def write():
            calls.append('effect')
            raise TransientFailure('TOOL_TIMEOUT', delay_seconds=0)
        return context.step('create-ticket', 'TOOL', 'WRITE', {'operation': 'create_ticket'}, write)

    executor = worker(store, handler)
    result = executor.run_once('TENANT_A')
    assert (result['state'], result['error_code']) == ('FAILED', 'SIDE_EFFECT_UNKNOWN')
    assert executor.run_once('TENANT_A') is None and calls == ['effect']
    assert store.steps('TENANT_A', row['run_id'])[0]['state'] == 'UNKNOWN'


@pytest.mark.parametrize('failure, expected', [(PermanentFailure('MODEL_UNAUTHORIZED'), 'MODEL_UNAUTHORIZED'),
                                               (ValueError('private-key-must-not-persist'), 'EXECUTION_ERROR')])
def test_permanent_and_unexpected_errors_are_terminal_and_sanitized(store, failure, expected):
    row = store.enqueue(request())
    def handler(context):
        raise failure
    result = worker(store, handler).run_once('TENANT_A')
    assert (result['state'], result['error_code'], result['retry_count']) == ('FAILED', expected, 0)
    assert 'private-key' not in str(store.get('TENANT_A', row['run_id']))


def test_worker_rejects_unknown_version_without_dynamic_import_or_handler_execution(store):
    store.enqueue(replace(request(), runtime_version='untrusted-module'))
    result = worker(store, lambda _: pytest.fail('unknown version executed')).run_once('TENANT_A')
    assert result['error_code'] == 'UNSUPPORTED_RUNTIME_VERSION'


def test_worker_slot_is_bounded_and_heartbeat_preserves_lease_during_io(store):
    row = store.enqueue(request()); store.enqueue(request(session='S2'))
    entered, release = Event(), Event()

    def handler(context):
        entered.set()
        assert release.wait(10)
        return {'outcome': 'complete'}

    executor = worker(store, handler, lease_seconds=1)
    with ThreadPoolExecutor(1) as pool:
        future = pool.submit(executor.run_once, 'TENANT_A')
        try:
            assert entered.wait(5)
            time.sleep(1.5)
            with admin() as connection:
                active = connection.execute('SELECT lease_until>clock_timestamp() AS valid FROM agent_run WHERE run_id=%s', (row['run_id'],)).fetchone()
                assert active['valid']
            assert executor.run_once('TENANT_A') is None
            assert store.get('TENANT_A', row['run_id'])['attempt'] == 1
        finally:
            release.set()
        assert future.result(timeout=10)['state'] == 'SUCCEEDED'


def test_other_tenant_progresses_while_a_full_tenant_waits_in_four_workers(store):
    store = RuntimeStore(store.connector, backlog_limit=12)
    original = request(session='burst-a-0')
    first = store.enqueue(original)
    for index in range(1, 12):
        store.enqueue(request(session=f'burst-a-{index}'))
    entered, release = Barrier(5), Event()

    def congested(context):
        def model_fixture():
            entered.wait(15)
            assert release.wait(30)
            return {'outcome': 'fixture-model-returned'}

        return context.step('held-model', 'MODEL', 'NONE', {'operation': 'fixture.model'}, model_fixture)

    def independent(context):
        return context.step('read-fixture', 'TOOL', 'READ', {'operation': 'fixture.read'},
                            lambda: {'outcome': 'fixture-read-completed'})

    with ThreadPoolExecutor(4) as pool:
        running = [pool.submit(worker(store, congested, tenant='TENANT_A').run_once, 'TENANT_A') for _ in range(4)]
        try:
            entered.wait(15)
            with pytest.raises(QueueFull):
                store.enqueue(request(session='over-limit'))
            assert store.enqueue(original)['run_id'] == first['run_id']
            assert all(not task.done() for task in running)
            started = time.perf_counter()
            scoped = worker(store, independent, tenant='TENANT_B')
            for index in range(3):
                pending = store.enqueue(request('TENANT_B', session=f'burst-b-{index}'))
                done = scoped.run_once('TENANT_B')
                assert done['run_id'] == pending['run_id'] and done['state'] == 'SUCCEEDED'
                assert done['model_calls'] == 0 and done['tool_calls'] == 1
            elapsed_ms = (time.perf_counter() - started) * 1000
            assert all(not task.done() for task in running)
            assert scoped.run_once('TENANT_A') is None
        finally:
            release.set()
        completed = [task.result(timeout=15) for task in running]

    assert len({row['run_id'] for row in completed}) == 4
    assert all(row['state'] == 'SUCCEEDED' and row['model_calls'] == 1 for row in completed)
    with admin() as database:
        a_pending = database.execute("SELECT count(*) AS n FROM agent_run WHERE tenant_code='TENANT_A' AND state='PENDING'").fetchone()['n']
        assert a_pending == 8
    report = {'environment': 'DISPOSABLE_POSTGRES_17_SYNTHETIC_HANDLERS', 'tenant_a_backlog_limit': 12,
              'tenant_a_concurrent_workers_held': len(completed), 'tenant_a_pending_after_release': a_pending,
              'tenant_a_overflow_rejected': 1, 'tenant_a_duplicate_reused': 1,
              'tenant_b_runs_completed_while_a_blocked': 3, 'tenant_b_enqueue_plus_execution_ms': round(elapsed_ms, 3)}
    path = Path(__file__).resolve().parents[2]/'.local/verification/runtime-tenant-burst.json'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')


def test_cancel_during_external_read_prevents_late_completion(store):
    row = store.enqueue(request()); entered, release = Event(), Event()

    def handler(context):
        def read():
            entered.set()
            assert release.wait(10)
            return {'resource_ref': 'late'}
        return context.step('lookup', 'TOOL', 'READ', {}, read)

    with ThreadPoolExecutor(1) as pool:
        future = pool.submit(worker(store, handler).run_once, 'TENANT_A')
        try:
            assert entered.wait(5)
            assert store.cancel('TENANT_A', row['run_id'])['state'] == 'CANCELLED'
        finally:
            release.set()
        assert future.result(timeout=10)['state'] == 'CANCELLED'
    assert store.steps('TENANT_A', row['run_id'])[0]['state'] == 'UNKNOWN'


def test_worker_budget_exhaustion_commits_failure_without_calling_model(store):
    row = store.enqueue(request(), Limits(max_model_calls=0))
    result = worker(store, lambda ctx: ctx.step('answer', 'MODEL', 'NONE', {}, lambda: pytest.fail('model executed'))).run_once('TENANT_A')
    assert result['error_code'] == 'BUDGET_EXHAUSTED'
    assert store.get('TENANT_A', row['run_id'])['model_calls'] == 0


def test_handler_loop_stops_at_shared_step_budget(store):
    row = store.enqueue(request(), Limits(max_steps=3))
    calls = []
    def handler(context):
        for index in range(100):
            context.step('iteration-' + str(index), 'MODEL', 'NONE', {}, lambda: calls.append(1) or {'outcome': 'continue'})
        pytest.fail('Unbounded loop escaped runtime budget')
    result = worker(store, handler).run_once('TENANT_A')
    assert result['error_code'] == 'BUDGET_EXHAUSTED' and len(calls) == 3
    assert store.get('TENANT_A', row['run_id'])['step_count'] == 3


def test_heartbeat_database_failure_revokes_local_authority_before_late_result(store):
    row = store.enqueue(request())
    from threading import current_thread
    class FailingHeartbeat:
        def __getattr__(self, name):
            return getattr(store, name)
        def heartbeat(self, lease, **kwargs):
            if current_thread().name == 'agent-lease-heartbeat':
                raise ConnectionError('private-connection-detail')
            return store.heartbeat(lease, **kwargs)
    def handler(context):
        assert context.lost.wait(5)
        return {'resource_ref': 'must-not-be-committed'}
    result = worker(FailingHeartbeat(), handler, lease_seconds=1).run_once('TENANT_A')
    assert result['state'] == 'RUNNING' and result['result_ref'] == {}
    assert 'private-connection-detail' not in str(result)
    from ai.runtime.types import Lease
    expire(Lease.from_run(store.get('TENANT_A', row['run_id'])))
    recovered = worker(store, lambda _: {'outcome': 'recovered'}).run_once('TENANT_A')
    assert recovered['state'] == 'SUCCEEDED' and recovered['attempt'] == 2


def test_changed_logical_step_intent_after_retry_is_rejected(store):
    store.enqueue(request())
    first = True
    calls = []
    def handler(context):
        nonlocal first
        context.step('lookup', 'TOOL', 'READ', {'operation': 'original' if first else 'changed'},
                     lambda: calls.append(1) or {'resource_ref': 'record-1'})
        if first:
            first = False
            raise TransientFailure('NEXT_STEP_TEMPORARY', delay_seconds=0)
        return {'outcome': 'done'}
    executor = worker(store, handler)
    assert executor.run_once('TENANT_A')['state'] == 'RETRY_WAIT'
    assert executor.run_once('TENANT_A')['error_code'] == 'EXECUTION_ERROR'
    assert calls == [1]


def test_tick_rotates_available_tenants_and_stopped_loop_claims_nothing(store):
    store.enqueue(request()); store.enqueue(request(session='S2')); store.enqueue(request('TENANT_B'))
    executor = worker(store, lambda _: {'outcome': 'done'})
    assert executor.tick()['tenant_code'] == 'TENANT_A'
    assert executor.tick()['tenant_code'] == 'TENANT_B'
    stop = Event(); stop.set()
    executor.run_forever(stop)
    assert executor.tick()['tenant_code'] == 'TENANT_A'


@pytest.mark.parametrize('mode', ['MODEL', 'WRITE'])
def test_real_process_kill_recovers_reads_and_holds_unknown_writes(store, tmp_path, mode):
    row = store.enqueue(request())
    marker = tmp_path/'reserved'
    # Credentials stay in the environment; no token/DSN enters argv or output.
    process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('runtime_worker_process.py')), mode, str(marker)],
                               env={**os.environ, 'PYTHONPATH': str(Path(__file__).resolve().parents[1])},
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        wait_until(lambda: marker.exists() or process.poll() is not None)
        assert marker.exists() and process.poll() is None
        assert [step['state'] for step in store.steps('TENANT_A', row['run_id'])] == ['SUCCEEDED', 'RESERVED']
    finally:
        if process.poll() is None:
            process.kill()
        process.wait(timeout=10)
    with admin() as connection:
        wait_until(lambda: connection.execute('SELECT lease_until<=clock_timestamp() AS expired FROM agent_run WHERE run_id=%s', (row['run_id'],)).fetchone()['expired'])
    calls = []

    def handler(context):
        context.step('prepare', 'INTERNAL', 'NONE', {'operation': 'prepare'}, lambda: pytest.fail('completed step replayed'))
        return context.step('external', 'MODEL', 'NONE', {'operation': 'external'}, lambda: calls.append(1) or {'resource_ref': 'answer'})

    result = worker(store, handler).run_once('TENANT_A')
    final = store.get('TENANT_A', row['run_id'])
    if mode == 'MODEL':
        assert result['state'] == 'SUCCEEDED' and calls == [1]
        assert (final['model_calls'], final['retry_count'], final['attempt']) == (2, 1, 2)
        assert [step['state'] for step in store.steps('TENANT_A', row['run_id'])] == ['SUCCEEDED', 'UNKNOWN', 'SUCCEEDED']
    else:
        assert result is None and calls == []
        assert (final['state'], final['error_code'], final['tool_calls']) == ('FAILED', 'SIDE_EFFECT_UNKNOWN', 1)
