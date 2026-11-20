"""Current write permission under real leases, cancellation, RLS and row locks."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import os
import json
import time
from uuid import uuid4

from fastapi.testclient import TestClient
import psycopg
from psycopg.rows import dict_row
import pytest

from ai.runtime.store import RuntimeStore
from ai.runtime.types import ActionDenied
from runtime_api_support import PATH, build_app, encode, signed, KEYS
from ai.runtime.authority import sign
from test_runtime_store_postgres import store, admin, request, acquire, expire

pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),
                                reason='requires disposable runtime PostgreSQL')


def setup(store, *, reserve=True, kind='TOOL', effect='WRITE', intent_change=None):
    value = replace(request(), principal_ref='visitor-session:7')
    row = store.enqueue(value)
    lease = acquire(store)
    snapshot = uuid4()
    digest = 'a' * 64
    intent = dict(operation='session.reply', resource_ref=value.principal_ref,
                  input_ref=str(snapshot), arguments_sha256=digest, version='reply-v1')
    if reserve: store.reserve_step(lease, 'reply-1', kind, effect, {**intent, **(intent_change or {})})
    return value, row, lease, snapshot, digest, intent


def authorize(store, values, **changes):
    value, row, lease, snapshot, digest, _ = values
    params = dict(tenant=value.tenant, run_id=row['run_id'], source_event_id=value.source_event_id,
                  owner=lease.owner, fence=lease.fence, snapshot_id=snapshot, arguments_hash=digest)
    return store.authorize_reply(**{**params, **changes})


def test_valid_short_authority_does_not_mutate_or_replenish_any_budget(store):
    values = setup(store)
    value, row, lease, snapshot, digest, _ = values
    before = store.get(value.tenant, row['run_id'])
    steps = store.steps(value.tenant, row['run_id'])
    with admin() as connection:
        now = connection.execute('SELECT clock_timestamp() AS now').fetchone()['now']
    grant = authorize(store, values)
    assert grant == dict(version='action-v1', tenant_code=value.tenant, run_id=str(row['run_id']),
                         source_event_id=str(value.source_event_id), owner=lease.owner, fence=lease.fence,
                         step_key='reply-1', step_no=1, operation='session.reply', snapshot_id=str(snapshot),
                         arguments_sha256=digest, expires_at_ms=grant['expires_at_ms'])
    assert now.timestamp()*1000 < grant['expires_at_ms'] <= min(now.timestamp()+5, before['lease_until'].timestamp(),
                                                              before['deadline_at'].timestamp())*1000
    assert authorize(store, values)['step_key'] == 'reply-1'
    assert store.get(value.tenant, row['run_id']) == before
    assert store.steps(value.tenant, row['run_id']) == steps
    assert authorize(store, values, tenant='TENANT_B') is None
    assert authorize(store, values, run_id=uuid4()) is None


@pytest.mark.parametrize('field,value', [('owner', 'other-worker'), ('fence', 99),
                                       ('source_event_id', uuid4()), ('snapshot_id', uuid4()),
                                       ('arguments_hash', 'b' * 64)])
def test_permission_binds_owner_fence_source_snapshot_and_exact_parameters(store, field, value):
    values = setup(store)
    with pytest.raises(ActionDenied): authorize(store, values, **{field: value})


@pytest.mark.parametrize('change', [dict(resource_ref='visitor-session:8'), dict(operation='ticket.create'),
                                  dict(version='reply-v2'), dict(input_ref=str(uuid4())),
                                  dict(arguments_sha256='b' * 64), dict(business_ref='unexpected')])
def test_persisted_intent_must_match_exact_business_boundary(store, change):
    values = setup(store, intent_change=change)
    with pytest.raises(ActionDenied): authorize(store, values)


@pytest.mark.parametrize('kind,effect', [('TOOL', 'READ'), ('MODEL', 'NONE'), ('INTERNAL', 'NONE')])
def test_read_and_model_steps_never_authorize_business_writes(store, kind, effect):
    values = setup(store, kind=kind, effect=effect)
    with pytest.raises(ActionDenied): authorize(store, values)


@pytest.mark.parametrize('state', ['SUCCEEDED', 'FAILED', 'UNKNOWN'])
def test_completed_failed_and_uncertain_steps_never_issue_new_write_permission(store, state):
    values = setup(store)
    store.complete_step(values[2], 'reply-1', state, {})
    with pytest.raises(ActionDenied): authorize(store, values)


def test_missing_and_ambiguous_write_steps_fail_closed(store):
    values = setup(store, reserve=False)
    with pytest.raises(ActionDenied): authorize(store, values)
    store.reserve_step(values[2], 'reply-1', 'TOOL', 'WRITE', values[-1])
    store.reserve_step(values[2], 'reply-2', 'TOOL', 'WRITE', values[-1])
    with pytest.raises(ActionDenied): authorize(store, values)


@pytest.mark.parametrize('transition', ['cancel', 'fail', 'retry', 'lease', 'deadline'])
def test_revocation_and_expiry_remove_authority(store, transition):
    values = setup(store)
    lease = values[2]
    if transition == 'cancel': store.cancel(lease.tenant, lease.run_id)
    if transition == 'fail': store.fail(lease, 'TEST_FAILURE')
    if transition == 'retry': store.retry(lease, 'TEST_RETRY', delay_seconds=0)
    if transition == 'lease': expire(lease)
    if transition == 'deadline': expire(lease, deadline=True)
    with pytest.raises(ActionDenied): authorize(store, values)


def test_pending_and_old_fence_cannot_mint_write_authority(store):
    values = setup(store)
    expire(values[2])
    # A lost WRITE blocks takeover rather than being silently retried.
    assert store.claim_next('TENANT_A', 'other-worker') is None
    with pytest.raises(ActionDenied): authorize(store, values)
    value = request(session='PENDING')
    row = store.enqueue(value)
    with pytest.raises(ActionDenied):
        store.authorize_reply(value.tenant, row['run_id'], value.source_event_id, 'worker-1', 1, uuid4(), 'a'*64)


def test_clock_is_checked_after_waiting_for_step_row_lock(store):
    values = setup(store)
    lease = values[2]
    # Hold only the step lock: authorization may acquire the Run lock before
    # blocking here. The lease must not be assessed using pre-lock time.
    with ThreadPoolExecutor(1) as pool:
        with admin() as blocker:
            blocker.execute("UPDATE agent_run SET lease_until=clock_timestamp()+interval '900 milliseconds' WHERE run_id=%s", (lease.run_id,))
            started = time.monotonic()
            with blocker.transaction():
                blocker.execute('SELECT 1 FROM agent_step WHERE run_id=%s FOR UPDATE', (lease.run_id,))
                observer_store = RuntimeStore(lambda: psycopg.connect(os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'],
                    row_factory=dict_row, application_name='supportflow-authority-lock-test'))
                future = pool.submit(authorize, observer_store, values)
                waiting = False
                while time.monotonic()-started < .65:
                    # pg_stat_activity snapshots are cached within this blocker
                    # transaction; each observation must explicitly refresh it.
                    blocker.execute('SELECT pg_stat_clear_snapshot()')
                    waiting = bool(blocker.execute("""SELECT 1 FROM pg_stat_activity
                        WHERE application_name='supportflow-authority-lock-test' AND wait_event_type='Lock'
                        AND query LIKE 'SELECT step_key,step_no FROM agent_step%'""").fetchone())
                    if waiting: break
                    time.sleep(.02)
                assert waiting, 'authorization did not reach the held step row'
                assert not future.done()
                time.sleep(max(0, 1.05-(time.monotonic()-started)))
            with pytest.raises(ActionDenied): future.result(timeout=3)


def test_authenticated_http_grant_then_cancellation_denial_with_real_store(store, monkeypatch):
    values = setup(store)
    value, row, lease, snapshot, digest, _ = values
    body = encode(dict(tenant_code=value.tenant, source_event_id=str(value.source_event_id), owner=lease.owner,
                       fence=lease.fence, snapshot_id=str(snapshot), arguments_sha256=digest))
    path = PATH + '/' + str(row['run_id']) + '/authorize'
    with TestClient(build_app(monkeypatch, store=store)) as client:
        response = client.post(path, content=body, headers=signed('POST', path, body))
        assert response.status_code == 200
        payload = response.json()
        signature = payload.pop('signature')
        assert sign(payload, KEYS['customer'])['signature'] == signature
        assert client.post(path, content=body, headers=signed('POST', path, body, tenant='TENANT_B')).status_code == 403
        other = encode({**json.loads(body), 'tenant_code': 'TENANT_B'})
        assert client.post(path, content=other, headers=signed('POST', path, other, tenant='TENANT_B')).status_code == 404
        store.cancel(value.tenant, row['run_id'])
        assert client.post(path, content=body, headers=signed('POST', path, body)).status_code == 409
