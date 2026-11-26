"""Signed review requests change only the exact persisted tenant proposal."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import os
from threading import Barrier
from uuid import uuid4

from fastapi.testclient import TestClient
import pytest

from ai.runtime.types import Lease, LostLease
from runtime_api_support import PATH, build_app
from test_approval_lifecycle_postgres import CONTENT, TITLE, decide, elapsed, wait
from test_runtime_approval_review import post
from test_runtime_store_postgres import admin, request, store

pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),
                                reason='requires disposable runtime PostgreSQL')


def setup(store, monkeypatch):
    source, waiting, approvals, row = wait(store)
    app = build_app(monkeypatch, store=store, approval_enabled=True)
    path = f"{PATH}/{row['run_id']}/review-ticket-approval"
    payload = dict(approval_id=str(row['approval_id']), source_event_id=str(source.source_event_id),
                   arguments_sha256=row['arguments_sha256'], reviewer_ref='staff:123', decision='approve')
    return app, path, payload, source, waiting, approvals, row


def test_wrong_tenant_event_hash_and_approval_cannot_change_new_wait(store, monkeypatch):
    app, path, payload, source, waiting, approvals, row = setup(store, monkeypatch)
    with TestClient(app) as client:
        assert post(client, path, payload, tenant='TENANT_B').status_code == 409
        for change in (dict(approval_id=str(uuid4())), dict(source_event_id=str(uuid4())), dict(arguments_sha256='b' * 64)):
            assert post(client, path, payload | change).status_code == 409
    assert store.get(source.tenant, row['run_id']) == waiting and approvals.get(
        source.tenant, row['run_id']) == row


def test_approval_repeat_keeps_original_staff_and_changed_revocation_is_blocked(store, monkeypatch):
    app, path, payload, source, waiting, approvals, row = setup(store, monkeypatch)
    with TestClient(app) as client:
        assert post(client, path, payload).json() == {'approval_id': str(row['approval_id']), 'status': 'APPROVED'}
        approved = approvals.get(source.tenant, row['run_id'])
        assert post(client, path, payload | {'reviewer_ref': 'staff:124'}).status_code == 200
        assert approvals.get(source.tenant, row['run_id']) == approved and approved['decided_by'] == 'staff:123'
        assert post(client, path, payload | {'decision': 'revoke',
                    'arguments_sha256': 'b' * 64}).status_code == 409
        assert post(client, path, payload | {'decision': 'revoke',
                    'reviewer_ref': 'staff:124'}).json()['status'] == 'REVOKED'
    final = store.get(source.tenant, row['run_id'])
    assert (final['state'], final['error_code'], final['step_count']) == (
        'FAILED', 'APPROVAL_REVOKED', waiting['step_count'])
    assert approvals.get(source.tenant, row['run_id'])['closed_by'] == 'staff:124'


def test_denial_never_queues_work_and_repeated_approve_conflicts(store, monkeypatch):
    app, path, payload, source, _, approvals, row = setup(store, monkeypatch)
    with TestClient(app) as client:
        assert post(client, path, payload | {'decision': 'deny'}).json()['status'] == 'DENIED'
        assert post(client, path, payload).status_code == 409
    assert store.claim_next(source.tenant, 'must-not-run') is None
    assert approvals.get(source.tenant, row['run_id'])['decided_by'] == 'staff:123'


@pytest.mark.parametrize('decision', ['approve', 'revoke'])
def test_expired_review_returns_conflict_after_recording_expiry(store, monkeypatch, decision):
    app, path, payload, source, _, approvals, row = setup(store, monkeypatch)
    with TestClient(app) as client:
        if decision == 'revoke':
            assert post(client, path, payload).status_code == 200
        elapsed(row)
        failure = post(client, path, payload | {'decision': decision})
        assert failure.status_code == 409 and failure.json() == {'detail': 'Approval has expired'}
    assert approvals.get(source.tenant, row['run_id'])['status'] == 'EXPIRED'
    assert store.get(source.tenant, row['run_id'])['error_code'] == 'APPROVAL_EXPIRED'


def test_preview_preserves_original_terms_and_never_advances_run(store, monkeypatch):
    app, review, command, source, waiting, approvals, row = setup(store, monkeypatch)
    route = f"{PATH}/{row['run_id']}/ticket-approval-preview"
    ref = {'source_event_id': str(source.source_event_id)}
    with TestClient(app) as client:
        result = post(client, route, ref)
        assert result.status_code == 200 and result.headers['cache-control'] == 'no-store'
        assert result.json() == dict(approval_id=str(row['approval_id']), status='PENDING',
                                     expires_at=result.json()['expires_at'], arguments_sha256=row['arguments_sha256'], title=TITLE, content=CONTENT)
        assert datetime.fromisoformat(result.json()['expires_at']) == row['expires_at']
        assert store.get(source.tenant, row['run_id']) == waiting
        assert approvals.get(source.tenant, row['run_id']) == row
        assert post(client, review, command).status_code == 200
        assert post(client, route, ref).json()['status'] == 'APPROVED'


def test_wrong_tenant_or_source_cannot_read_terms(store, monkeypatch):
    app, _, _, source, _, _, row = setup(store, monkeypatch)
    route = f"{PATH}/{row['run_id']}/ticket-approval-preview"
    with TestClient(app) as client:
        wrong_tenant = post(client, route, {'source_event_id': str(source.source_event_id)}, tenant='TENANT_B')
        wrong_event = post(client, route, {'source_event_id': str(uuid4())})
    assert wrong_tenant.status_code == wrong_event.status_code == 404
    assert wrong_tenant.json() == wrong_event.json() == {'detail': 'Approval proposal not found'}


@pytest.mark.parametrize('close', ['deny', 'revoke', 'expires'])
def test_closed_or_expired_terms_are_unavailable(store, monkeypatch, close):
    app, review, command, source, _, _, row = setup(store, monkeypatch)
    route = f"{PATH}/{row['run_id']}/ticket-approval-preview"
    with TestClient(app) as client:
        if close == 'revoke':
            assert post(client, review, command).status_code == 200
        if close == 'expires':
            elapsed(row)
        else:
            assert post(client, review, command | {'decision': close}).status_code == 200
        before = store.get(source.tenant, row['run_id'])
        assert post(client, route, {'source_event_id': str(source.source_event_id)}).status_code == 404
    assert store.get(source.tenant, row['run_id']) == before


def test_invalid_checkpoint_digest_never_returns_terms(store, monkeypatch):
    app, _, _, source, _, _, row = setup(store, monkeypatch)
    route = f"{PATH}/{row['run_id']}/ticket-approval-preview"
    with admin() as connection:
        connection.execute('UPDATE agent_payload SET payload_hash=%s WHERE run_id=%s', ('0' * 64, row['run_id']))
    with TestClient(app) as client:
        reply = post(client, route, {'source_event_id': str(source.source_event_id)})
    assert reply.status_code == 409 and reply.json() == {'detail': 'Approval proposal is unavailable'}


def test_active_reference_is_only_run_and_source_metadata_for_this_session(store, monkeypatch):
    app, review, command, source, waiting, approvals, row = setup(store, monkeypatch)
    route, ref = f'{PATH}/ticket-approval-active', {'session_no': source.session_no}
    with TestClient(app) as client:
        pending = post(client, route, ref)
        assert pending.status_code == 200 and pending.headers['cache-control'] == 'no-store'
        body = pending.json()
        assert body == dict(run_id=str(row['run_id']), source_event_id=str(source.source_event_id),
                            status='PENDING', expires_at=body['expires_at'])
        assert datetime.fromisoformat(body['expires_at']) == row['expires_at']
        assert store.get(source.tenant, row['run_id']) == waiting and approvals.get(
            source.tenant, row['run_id']) == row
        assert post(client, route, ref, tenant='TENANT_B').status_code == 404
        assert post(client, route, {'session_no': 'OTHER_SESSION'}).status_code == 404
        assert post(client, review, command).status_code == 200
        assert post(client, route, ref).json()['status'] == 'APPROVED'
        assert post(client, review, command | {'decision': 'revoke'}).status_code == 200
        assert post(client, route, ref).status_code == 404


def test_elapsed_approval_is_hidden_even_before_worker_settles_it(store, monkeypatch):
    app, _, _, source, waiting, approvals, row = setup(store, monkeypatch)
    elapsed(row)
    with TestClient(app) as client:
        reply = post(client, f'{PATH}/ticket-approval-active', {'session_no': source.session_no})
    assert reply.status_code == 404 and reply.json() == {'detail': 'Active approval not found'}
    assert store.get(source.tenant, row['run_id']) == waiting
    assert approvals.get(source.tenant, row['run_id'])['status'] == 'PENDING'


def test_latest_terminal_progress_ignores_denied_unreviewed_and_live_runs(store, monkeypatch):
    app, _, _, source, _, approvals, _ = setup(store, monkeypatch)
    route, ref = f'{PATH}/ticket-approval-latest-progress', {'session_no': source.session_no}
    with TestClient(app) as client:
        assert post(client, route, ref).status_code == 404
    # Start a different session for a deterministic event ordering comparison.
    selected = None
    for sequence, should_approve in [(1, True), (2, True), (3, False)]:
        candidate = request(session='PROGRESS_ORDER', seq=sequence)
        _, _, approvals, row = wait(store, source=candidate)
        decide(approvals, candidate, row, approve=should_approve)
        if should_approve:
            approvals.revoke(candidate.tenant, row['run_id'], row['approval_id'], 'staff:124')
            selected = row
    unreviewed = request(session='PROGRESS_ORDER', seq=4)
    _, _, _, unfinished = wait(store, source=unreviewed)
    store.cancel(unreviewed.tenant, unfinished['run_id'])
    candidate = request(session='PROGRESS_ORDER', seq=5)
    _, _, approvals, live = wait(store, source=candidate)
    decide(approvals, candidate, live)
    state = store.get(candidate.tenant, selected['run_id'])
    live_before = store.get(candidate.tenant, live['run_id'])
    with TestClient(app) as client:
        result = post(client, route, {'session_no': candidate.session_no})
        assert result.status_code == 200 and result.headers['cache-control'] == 'no-store'
        assert result.json() == dict(tenant_code=candidate.tenant, session_no=candidate.session_no,
                                     source_event_id=str(state['source_event_id']), run_id=str(state['run_id']), snapshot_id=str(selected['snapshot_id']),
                                     state='FAILED', attempt=state['attempt'], step_count=state['step_count'])
        assert post(client, route, {'session_no': candidate.session_no}, tenant='TENANT_B').status_code == 404
        assert post(client, route, {'session_no': 'OTHER_SESSION'}).status_code == 404
    assert store.get(candidate.tenant, selected['run_id']) == state
    assert store.get(candidate.tenant, live['run_id']) == live_before


def test_latest_progress_follows_consumed_approval_through_execution_and_retry(store, monkeypatch):
    app, review, decision, source, _, approvals, row = setup(store, monkeypatch)
    tenant, run = source.tenant, row['run_id']
    latest = f'{PATH}/ticket-approval-latest-progress'
    ref = {'session_no': source.session_no}
    intent = dict(operation='ticket.create', version='ticket-create-v1', resource_ref=source.principal_ref,
                  input_ref=str(row['snapshot_id']), arguments_sha256=row['arguments_sha256'])

    def check_progress(client, expected_state):
        before = store.get(tenant, run)
        result = post(client, latest, ref)
        assert result.status_code == 200 and result.headers['cache-control'] == 'no-store'
        assert result.json() == dict(tenant_code=tenant, session_no=source.session_no,
                                     source_event_id=str(source.source_event_id), run_id=str(run), snapshot_id=str(row['snapshot_id']),
                                     state=expected_state, attempt=before['attempt'], step_count=before['step_count'])
        assert store.get(tenant, run) == before

    with TestClient(app) as client:
        assert post(client, review, decision).status_code == 200
        lease = Lease.from_run(store.claim_next(tenant, 'first-approved-worker'))
        assert post(client, latest, ref).status_code == 404
        step, created = store.reserve_step(lease, 'approved-ticket', 'TOOL', 'WRITE', intent)
        assert created
        consumed = approvals.get(tenant, run)
        assert consumed['status'] == 'CONSUMED' and consumed['consumed_step_no'] == step['step_no']
        check_progress(client, 'RUNNING')
        assert post(client, f'{PATH}/ticket-approval-active', ref).status_code == 404
        assert post(client, latest, ref, tenant='TENANT_B').status_code == 404

        store.complete_step(lease, step['step_key'], 'SUCCEEDED', {'business_ref': 'ticket:123'})
        store.retry(lease, 'SYNTHETIC_ACK_LOST', delay_seconds=0)
        check_progress(client, 'RETRY_WAIT')
        resumed = Lease.from_run(store.claim_next(tenant, 'second-approved-worker'))
        check_progress(client, 'RUNNING')
        store.finish(resumed, {'outcome': 'receipt-confirmed'})
        check_progress(client, 'SUCCEEDED')

    assert approvals.get(tenant, run) == consumed
    assert [write['step_no'] for write in store.steps(tenant, run) if write['effect'] == 'WRITE'] == [
        step['step_no']]


def cancellation(source, row):
    return f"{PATH}/{row['run_id']}/ticket-approval-cancel", {
        'source_event_id': str(source.source_event_id), 'snapshot_id': str(row['snapshot_id']), 'reviewer_ref': 'staff:124',
    }


def test_staff_cancel_is_bound_to_approved_source_and_replays_the_same_terminal_state(store, monkeypatch):
    app, _, _, source, waiting, approvals, row = setup(store, monkeypatch)
    tenant, run = source.tenant, row['run_id']
    route, command = cancellation(source, row)
    with TestClient(app) as client:
        assert post(client, route, command).status_code == 409
        assert store.get(tenant, run) == waiting
        decide(approvals, source, row)
        for field in ('source_event_id', 'snapshot_id'):
            assert post(client, route, command | {field: str(uuid4())}).status_code == 404
        assert post(client, route, command, tenant='TENANT_B').status_code == 404
        assert post(client, route, command, caller='ops').status_code == 403
        assert post(client, route, command | {'reviewer_ref': 'staff:9223372036854775808'}).status_code == 422
        first = post(client, route, command)
        assert first.status_code == 200 and first.headers['cache-control'] == 'no-store'
        assert first.json() == dict(tenant_code=tenant, run_id=str(run), source_event_id=str(source.source_event_id),
                                    snapshot_id=str(row['snapshot_id']), state='CANCELLED', attempt=waiting['attempt'],
                                    step_count=waiting['step_count'], write_status=None)
        final = store.get(tenant, run)
        assert post(client, route, command | {'reviewer_ref': 'staff:125'}).json() == first.json()
    assert store.get(tenant, run) == final
    assert store.claim_next(tenant, 'must-not-run') is None
    assert approvals.get(tenant, run)['closed_by'] == 'staff:124'
    page = approvals.event_page(tenant, run, source.source_event_id, row['snapshot_id'], 0)
    assert sum(event['event_type'] == 'run_cancelled' for event in page['events']) == 1


@pytest.mark.parametrize(('write_stage', 'state', 'write_status'), [
    ('reserved', 'FAILED', 'UNCERTAIN'),
    ('committed', 'RUNNING', 'COMMITTED'),
    ('finished', 'SUCCEEDED', None),
])
def test_staff_cancel_distinguishes_reserved_confirmed_and_finished_writes(store, monkeypatch, write_stage, state, write_status):
    app, _, _, source, _, approvals, row = setup(store, monkeypatch)
    route, command = cancellation(source, row)
    tenant, run = source.tenant, row['run_id']
    decide(approvals, source, row)
    lease = Lease.from_run(store.claim_next(tenant, 'approved-writer'))
    intent = dict(operation='ticket.create', version='ticket-create-v1', resource_ref=source.principal_ref,
                  input_ref=str(row['snapshot_id']), arguments_sha256=row['arguments_sha256'])
    store.reserve_step(lease, 'ticket-write', 'TOOL', 'WRITE', intent)
    receipt = {'business_ref': 'ticket:123'}
    if write_stage != 'reserved':
        store.complete_step(lease, 'ticket-write', 'SUCCEEDED', receipt)
    if write_stage == 'finished':
        store.finish(lease, receipt)
    before = store.get(tenant, run)
    with TestClient(app) as client:
        result = post(client, route, command)
        assert result.status_code == 200
        assert (result.json()['state'], result.json()['write_status']) == (state, write_status)
        assert post(client, route, command).json() == result.json()
        if write_stage == 'reserved':
            latest = post(client, f'{PATH}/ticket-approval-latest-progress',
                          {'session_no': source.session_no}).json()
            assert latest['write_status'] == 'UNCERTAIN'
            assert store.get(tenant, run)['error_code'] == 'SIDE_EFFECT_UNKNOWN'
            with pytest.raises(LostLease):
                store.finish(lease, receipt)
        else:
            assert store.get(tenant, run) == before
            assert [step['result_ref']
                    for step in store.steps(tenant, run) if step['effect'] == 'WRITE'] == [receipt]
    if write_stage == 'committed':
        assert store.finish(lease, receipt)['state'] == 'SUCCEEDED'


def test_staff_cancel_racing_finish_leaves_one_terminal_event(store, monkeypatch):
    app, _, _, source, _, approvals, row = setup(store, monkeypatch)
    tenant, run = source.tenant, row['run_id']
    decide(approvals, source, row)
    lease = Lease.from_run(store.claim_next(tenant, 'racing-worker'))
    route, body = cancellation(source, row)
    gate = Barrier(2)

    def finish():
        gate.wait(timeout=5)
        try:
            return store.finish(lease, {'outcome': 'finished'})['state']
        except LostLease:
            return 'STALE'

    with TestClient(app) as client, ThreadPoolExecutor(2) as pool:
        worker = pool.submit(finish)
        gate.wait(timeout=5)
        response = post(client, route, body)
        assert response.status_code == 200
        reply = response.json()
    final = store.get(tenant, run)
    assert (worker.result(), reply['state']) in [('SUCCEEDED', 'SUCCEEDED'), ('STALE', 'CANCELLED')]
    assert final['state'] == reply['state']
    page = approvals.event_page(tenant, run, source.source_event_id, row['snapshot_id'], 0)
    assert [event['event_type'] for event in page['events'] if event['event_type'] in ('run_completed', 'run_cancelled')] == [
        'run_completed' if final['state'] == 'SUCCEEDED' else 'run_cancelled',
    ]
