"""Committed approval events and signed cursor recovery against restricted PostgreSQL."""
from concurrent.futures import ThreadPoolExecutor
import os
from uuid import uuid4

from fastapi.testclient import TestClient
import psycopg
import pytest

from ai.core import schema_guard
from ai.runtime.types import Lease
from runtime_api_support import PATH, build_app
from test_approval_lifecycle_postgres import decide, wait
from test_runtime_approval_review import post
from test_runtime_store_postgres import admin, store

pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'), reason='requires disposable runtime PostgreSQL')


def signed_reference(source, approval):
    return f"{PATH}/{approval['run_id']}/ticket-approval-events", {
        'source_event_id': str(source.source_event_id), 'snapshot_id': str(approval['snapshot_id']), 'after_seq': 0,
    }


def test_event_pages_follow_commit_order_and_resume_without_duplicate_effects(store, monkeypatch):
    source, _, approvals, approval = wait(store)
    tenant, run = source.tenant, approval['run_id']
    route, ref = signed_reference(source, approval)
    app = build_app(monkeypatch, store=store, approval_enabled=True)
    with TestClient(app) as client:
        first = post(client, route, ref)
        assert first.status_code == 200 and first.headers['cache-control'] == 'no-store'
        initial = first.json()
        assert initial['schema_version'] == 'agent-run-events-v1' and initial['next_seq'] == 4
        assert [row['event_type'] for row in initial['events']] == [
            'run_started', 'step_started', 'step_completed', 'approval_required',
        ]
        assert not initial['has_more']
        for changed in ({'snapshot_id': str(uuid4())}, {'source_event_id': str(uuid4())}):
            assert post(client, route, ref | changed).status_code == 404
        for invalid in (-1, True, '0', 2147483648):
            assert post(client, route, ref | {'after_seq': invalid}).status_code == 422
        assert post(client, route, ref, tenant='TENANT_B').status_code == 404
        assert post(client, route, ref, caller='ops').status_code == 403
        assert client.post(route, json=ref).status_code == 401
        assert post(client, route, ref | {'after_seq': 5}).status_code == 409

    decide(approvals, source, approval)
    lease = Lease.from_run(store.claim_next(tenant, 'event-worker'))
    private_intent = {'resource_ref': 'synthetic-private-tool-input'}
    with ThreadPoolExecutor(max_workers=2) as pool:
        reserved = sorted(pool.map(lambda key: store.reserve_step(lease, key, 'TOOL', 'READ', private_intent)[0],
                                   ['parallel-one', 'parallel-two']), key=lambda step: step['step_no'])
    for step in reversed(reserved):
        store.complete_step(lease, step['step_key'], 'SUCCEEDED', {'resource_ref': 'synthetic-private-tool-output'})
    store.complete_step(lease, reserved[0]['step_key'], 'SUCCEEDED', {'resource_ref': 'synthetic-private-tool-output'})
    for number in range(7):
        key = f'page-step-{number}'
        store.reserve_step(lease, key, 'INTERNAL', 'NONE', {})
        store.complete_step(lease, key, 'SUCCEEDED', {})
    store.finish(lease, {'outcome': 'finished'})
    before = store.get(tenant, run)

    # Reopen the client and reconstruct the store after other transactions committed.
    restarted_store = type(store)(store.connector)
    with TestClient(build_app(monkeypatch, store=restarted_store, approval_enabled=True)) as client:
        resumed_ref = ref | {'after_seq': initial['next_seq']}
        page = post(client, route, resumed_ref).json()
        assert page == post(client, route, resumed_ref).json()
        assert page['has_more'] and page['next_seq'] == 20 and len(page['events']) == 16
        assert [(event['event_type'], event['step_no']) for event in page['events'][:4]] == [
            ('tool_started', 2), ('tool_started', 3), ('tool_completed', 3), ('tool_completed', 2),
        ]
        last = post(client, route, ref | {'after_seq': page['next_seq']}).json()
        assert not last['has_more'] and last['events'][-1]['event_type'] == 'run_completed'
        events = initial['events'] + page['events'] + last['events']
        assert [event['seq'] for event in events] == list(range(1, 24))
        assert all(set(event) == {'seq', 'event_type', 'step_no', 'occurred_at', 'outcome'} for event in events)
        assert 'synthetic-private-tool' not in str(events)
        empty = post(client, route, ref | {'after_seq': last['next_seq']}).json()
        assert empty['next_seq'] == 23 and empty['events'] == [] and not empty['has_more']
    assert store.get(tenant, run) == before


def test_event_rows_are_atomic_with_the_source_transition_and_cancel(store, monkeypatch):
    source, waiting, approvals, row = wait(store)
    tenant, run = source.tenant, row['run_id']
    route, ref = signed_reference(source, row)
    app = build_app(monkeypatch, store=store, approval_enabled=True)
    with TestClient(app) as client:
        with pytest.raises(RuntimeError, match='fixture rollback'), admin() as db, db.transaction():
            db.execute("UPDATE agent_run SET state='FAILED' WHERE tenant_code=%s AND run_id=%s", (tenant, run))
            assert db.execute('SELECT event_type FROM agent_run_event WHERE run_id=%s AND seq=5',
                              (run,)).fetchone()['event_type'] == 'run_failed'
            assert post(client, route, ref | {'after_seq': 4}).json()['events'] == []
            raise RuntimeError('fixture rollback')
        assert store.get(tenant, run) == waiting
        decide(approvals, source, row)
        lease = Lease.from_run(store.claim_next(tenant, 'cancel-event-worker'))
        store.reserve_step(lease, 'read-in-flight', 'TOOL', 'READ', {})
        store.cancel(tenant, run)
        page = post(client, route, ref | {'after_seq': 4}).json()
        assert [(event['seq'], event['event_type'], event['outcome']) for event in page['events']] == [
            (5, 'tool_started', None), (6, 'tool_completed', 'UNKNOWN'), (7, 'run_cancelled', None),
        ]


def test_event_journal_is_read_only_and_readiness_detects_trigger_drift(store, monkeypatch):
    source, _, _, _ = wait(store)
    with store._scope(source.tenant) as db:
        for statement in ('INSERT INTO agent_run_event DEFAULT VALUES', 'UPDATE agent_run_event SET seq=99',
                          'DELETE FROM agent_run_event', 'TRUNCATE agent_run_event'):
            with pytest.raises(psycopg.errors.InsufficientPrivilege), db.transaction():
                db.execute(statement)
    for scope in ('', 'PLATFORM', 'TENANT_B'):
        with store.connector() as db:
            db.execute("SELECT set_config('app.tenant_code',%s,true)", (scope,))
            assert db.execute('SELECT count(*) AS total FROM agent_run_event').fetchone()['total'] == 0

    monkeypatch.setattr(schema_guard, 'connect', lambda **kwargs: psycopg.connect(
        os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'], row_factory=psycopg.rows.dict_row, **kwargs))
    assert schema_guard.runtime_missing() == {}
    with admin() as db:
        db.execute('ALTER TABLE agent_step DISABLE TRIGGER agent_step_state_event')
    try:
        assert schema_guard.runtime_missing()['ai_db.agent_run_event.triggers'] == schema_guard.RUN_EVENT_SOURCE
    finally:
        with admin() as db:
            db.execute('ALTER TABLE agent_step ENABLE TRIGGER agent_step_state_event')
    assert schema_guard.runtime_missing() == {}
