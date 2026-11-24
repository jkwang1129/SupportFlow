"""Trusted pause/decision/resume behavior against restricted PostgreSQL."""
from concurrent.futures import ThreadPoolExecutor
import os
from threading import Barrier, Event
from uuid import uuid4

import psycopg
from psycopg.types.json import Jsonb
import pytest

from ai.runtime.approvals import TicketApprovals
from ai.runtime.types import ActionDenied, Lease, LostLease, RuntimeConflict
from ai.runtime.worker import ExecutionContext, RuntimeWorker
from test_runtime_store_postgres import admin, request, store

pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'), reason='requires disposable runtime PostgreSQL')
TITLE, CONTENT = '工单标题🙂', '合成内容\r\nexact '


def wait(store, *, wait_seconds=60, source=None):
    source = source or request()
    run = store.enqueue(source)['run_id']
    approvals = TicketApprovals(store)
    snapshot = uuid4()
    runner = RuntimeWorker(store, {'runtime-v1': lambda context: approvals.pause(
        context, snapshot, TITLE, CONTENT, wait_seconds=wait_seconds)}, 'first-worker')
    state = runner.run_once(source.tenant)
    assert state['state'] == 'WAITING_APPROVAL'
    row = approvals.get(source.tenant, run)
    return source, state, approvals, row


def decide(approvals, source, row, *, approve=True, reviewer='reviewer:1'):
    return approvals.decide(source.tenant, row['run_id'], row['approval_id'], reviewer, approve=approve)


def elapsed(row):
    # Set both dates in the disposable fixture so expiry retains a valid interval.
    with admin() as connection:
        connection.execute("""UPDATE agent_approval SET requested_at=clock_timestamp()-interval '3 minutes',
            expires_at=clock_timestamp()-interval '2 minutes',decided_at=CASE WHEN decided_at IS NOT NULL
                THEN clock_timestamp()-interval '150 seconds' END WHERE run_id=%s""", (row['run_id'],))


def test_pause_releases_old_lease_and_new_worker_loads_exact_approved_checkpoint(store):
    source, before, approvals, row = wait(store)
    assert before['owner'] is None and before['lease_until'] is None and before['step_count'] == 1
    assert before['model_calls'] == before['tool_calls'] == before['retry_count'] == 0
    assert TITLE not in str(before) and CONTENT not in str(row)
    assert row['expires_at'] <= before['deadline_at']
    assert store.claim_next(source.tenant, 'other-worker') is None
    with pytest.raises(LostLease):
        store.heartbeat(Lease(source.tenant, row['run_id'], 'first-worker', before['fence']-1))
    assert decide(approvals, source, row)['status'] == 'APPROVED'
    assert decide(approvals, source, row, reviewer='reviewer:2')['decided_by'] == 'reviewer:1'

    def second_worker(ctx):
        data = TicketApprovals(store).approved_arguments(ctx)
        assert data == dict(snapshot_id=row['snapshot_id'], title=TITLE, content=CONTENT, arguments_sha256=row['arguments_sha256'])
        assert ctx.lease.fence > before['fence']
        return {'outcome': 'resume-proof'}

    after = RuntimeWorker(store, {'runtime-v1': second_worker}, 'second-worker').run_once(source.tenant)
    assert after['state'] == 'SUCCEEDED' and after['deadline_at'] == before['deadline_at']
    for field in ('step_count', 'tool_calls', 'model_calls', 'retry_count'):
        assert after[field] == before[field]


def test_denial_leaves_history_and_unblocks_next_event_without_execution(store):
    source, before, approvals, row = wait(store)
    pending = store.enqueue(request(seq=2))
    assert store.claim_next(source.tenant, 'later-worker') is None
    denied = decide(approvals, source, row, approve=False)
    assert denied['status'] == 'DENIED' and decide(approvals, source, row, approve=False) == denied
    with pytest.raises(RuntimeConflict):
        decide(approvals, source, row)
    state = store.get(source.tenant, row['run_id'])
    assert (state['state'], state['error_code'], state['step_count']) == ('FAILED', 'APPROVAL_DENIED', before['step_count'])
    assert store.claim_next(source.tenant, 'later-worker')['run_id'] == pending['run_id']


@pytest.mark.parametrize('approved', [False, True])
def test_expiry_uses_db_clock_before_or_after_positive_decision(store, approved):
    source, before, approvals, row = wait(store)
    if approved:
        decide(approvals, source, row)
    elapsed(row)
    expired = decide(approvals, source, row) if not approved else approvals.expire(source.tenant, row['run_id'], row['approval_id'])
    state = store.get(source.tenant, row['run_id'])
    assert expired['status'] == 'EXPIRED' and (expired['decided_by'] is not None) == approved
    assert state['state'] == 'FAILED' and state['error_code'] == 'APPROVAL_EXPIRED'
    assert state['step_count'] == before['step_count'] and state['tool_calls'] == 0


@pytest.mark.parametrize('claimed', [False, True])
def test_revocation_of_approved_request_fences_any_resumed_worker(store, claimed):
    source, _, approvals, row = wait(store)
    decide(approvals, source, row)
    lease = Lease.from_run(store.claim_next(source.tenant, 'follow-up')) if claimed else None
    result = approvals.revoke(source.tenant, row['run_id'], row['approval_id'], 'reviewer:revoker')
    assert (result['status'], result['decided_by'], result['closed_by']) == ('REVOKED', 'reviewer:1', 'reviewer:revoker')
    assert store.get(source.tenant, row['run_id'])['error_code'] == 'APPROVAL_REVOKED'
    if lease:
        with pytest.raises(LostLease):
            store.heartbeat(lease)
    else:
        assert store.claim_next(source.tenant, 'follow-up') is None


@pytest.mark.parametrize('approved', [False, True])
def test_run_cancellation_closes_pending_or_approved_authority(store, approved):
    source, _, approvals, row = wait(store)
    if approved:
        decide(approvals, source, row)
    cancelled = store.cancel(source.tenant, row['run_id'])
    record = approvals.get(source.tenant, row['run_id'])
    assert cancelled['state'] == 'CANCELLED' and cancelled['cancel_requested']
    assert record['status'] == 'CANCELLED' and (record['decided_at'] is not None) == approved
    with pytest.raises(RuntimeConflict):
        decide(approvals, source, row)


def test_foreign_scope_wrong_approval_or_bad_reviewer_cannot_decide(store):
    source, _, approvals, row = wait(store)
    assert approvals.get('TENANT_B', row['run_id']) is None
    for tenant, approval in (('TENANT_B', row['approval_id']), (source.tenant, uuid4())):
        with pytest.raises(RuntimeConflict):
            approvals.decide(tenant, row['run_id'], approval, 'reviewer:1', approve=True)
    for actor, choice in (('untrusted actor', True), ('reviewer:1', 1)):
        with pytest.raises(ValueError):
            approvals.decide(source.tenant, row['run_id'], row['approval_id'], actor, approve=choice)
    assert approvals.get(source.tenant, row['run_id']) == row


def test_six_same_decisions_converge_to_one_reviewer_and_no_budget_change(store):
    source, before, approvals, row = wait(store)
    start = Barrier(6)

    def worker(index):
        start.wait(timeout=10)
        return decide(approvals, source, row, reviewer=f'reviewer:{index}')

    with ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(worker, range(6)))
    assert results[0]['status'] == 'APPROVED' and all(item == results[0] for item in results)
    after = store.get(source.tenant, row['run_id'])
    assert after == before | dict(state='PENDING', ready_at=after['ready_at'], updated_at=after['updated_at'])


def test_cancel_and_approve_race_never_leaves_active_approval(store):
    source, _, approvals, row = wait(store)
    barrier = Barrier(2)

    def approve():
        barrier.wait(timeout=5)
        try:
            return decide(approvals, source, row)
        except RuntimeConflict:
            return None

    with ThreadPoolExecutor(max_workers=2) as pool:
        decision = pool.submit(approve)
        barrier.wait(timeout=5)
        assert store.cancel(source.tenant, row['run_id'])['state'] == 'CANCELLED'
        decision.result(timeout=10)
    assert approvals.get(source.tenant, row['run_id'])['status'] == 'CANCELLED'


def test_pause_transaction_failure_reuses_committed_preparation_on_retry(store, monkeypatch):
    source = request()
    run = store.enqueue(source)['run_id']
    approvals = TicketApprovals(store)
    snapshot = uuid4()
    actual = approvals._approval
    crashed = False

    def fail_once(*args):
        nonlocal crashed
        if not crashed:
            crashed = True
            raise psycopg.OperationalError('synthetic pause transaction failure')
        return actual(*args)

    monkeypatch.setattr(approvals, '_approval', fail_once)
    runner = RuntimeWorker(store, {'runtime-v1': lambda ctx: approvals.pause(ctx, snapshot, TITLE, CONTENT)}, 'retry-worker')
    assert runner.run_once(source.tenant)['state'] == 'RETRY_WAIT'
    assert approvals.get(source.tenant, run) is None
    state = runner.run_once(source.tenant)
    assert state['state'] == 'WAITING_APPROVAL' and state['step_count'] == state['retry_count'] == 1


@pytest.mark.parametrize('fault', ['bad_reference', 'changed_body', 'expired'])
def test_resumed_checkpoint_fails_closed_if_binding_or_content_changed(store, fault):
    source, _, approvals, row = wait(store)
    decide(approvals, source, row)
    active = store.claim_next(source.tenant, 'new-worker')
    if fault == 'expired':
        elapsed(row)
    else:
        with admin() as connection:
            if fault == 'bad_reference':
                connection.execute("UPDATE agent_run SET result_ref=jsonb_set(result_ref,'{resource_ref}',%s) WHERE run_id=%s",
                                   (Jsonb(str(uuid4())), row['run_id']))
            else:
                from ai.runtime.payloads import encode
                body, digest = encode('working-state-v1', dict(title='changed', content=CONTENT,
                    snapshot_id=str(row['snapshot_id']), arguments_sha256=row['arguments_sha256']))
                connection.execute('UPDATE agent_payload SET payload_json=%s,payload_hash=%s WHERE run_id=%s',
                                   (body, digest, row['run_id']))
    with pytest.raises(RuntimeConflict):
        approvals.approved_arguments(ExecutionContext(store, active, Event(), 30))


def test_missing_consumption_marker_blocks_permit_even_for_a_reserved_write(store):
    source, _, approvals, row = wait(store)
    decide(approvals, source, row)
    lease = Lease.from_run(store.claim_next(source.tenant, 'unexpected-writer'))
    store.reserve_step(lease, 'unconsumed-write', 'TOOL', 'WRITE', dict(operation='ticket.create', version='ticket-create-v1',
        resource_ref=source.principal_ref, input_ref=str(row['snapshot_id']), arguments_sha256=row['arguments_sha256']))
    # Fault the durable metadata to verify the permit independently checks it.
    with admin() as connection:
        connection.execute("UPDATE agent_approval SET status='APPROVED',consumed_step_no=NULL,consumed_at=NULL WHERE run_id=%s", (row['run_id'],))
    with pytest.raises(ActionDenied):
        store.authorize_ticket_create(source.tenant, row['run_id'], source.source_event_id, lease.owner, lease.fence,
                                      row['snapshot_id'], row['arguments_sha256'])
