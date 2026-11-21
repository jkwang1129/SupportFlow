"""Approval consumption must be fenced and atomic with its one tool reservation."""
import os
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier, Event
from uuid import uuid4

import psycopg
import pytest

from ai.runtime.types import ActionDenied, Lease, LostLease, RuntimeConflict
from ai.runtime.approvals import TicketApprovals
from ai.runtime.worker import ExecutionContext
from test_approval_lifecycle_postgres import CONTENT, TITLE, decide, elapsed, wait
from test_runtime_store_postgres import admin, request, store

pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'), reason='requires disposable runtime PostgreSQL')


def setup(store):
    source, _, approval, row = wait(store)
    decide(approval, source, row)
    lease = Lease.from_run(store.claim_next(source.tenant, 'approved-worker'))
    intent = dict(operation='ticket.create', version='ticket-create-v1', resource_ref=source.principal_ref,
                  input_ref=str(row['snapshot_id']), arguments_sha256=row['arguments_sha256'])
    return source, approval, row, lease, intent


def reserve(store, lease, intent, key='approved-write'):
    return store.reserve_step(lease, key, 'TOOL', 'WRITE', intent)


def test_step_reservation_consumes_approval_and_allows_only_the_bound_permit(store):
    source, approval, row, lease, intent = setup(store)
    step, created = reserve(store, lease, intent)
    assert created and reserve(store, lease, intent) == (step, False)
    record = approval.get(source.tenant, row['run_id'])
    assert record['status'] == 'CONSUMED' and record['consumed_step_no'] == step['step_no']
    grant = store.authorize_ticket_create(source.tenant, lease.run_id, source.source_event_id, lease.owner, lease.fence,
                                          row['snapshot_id'], row['arguments_sha256'])
    assert grant['step_no'] == step['step_no'] and grant['expires_at_ms'] < int(record['expires_at'].timestamp()*1000)
    with pytest.raises(ActionDenied):
        reserve(store, lease, intent, 'second-write')
    with pytest.raises(RuntimeConflict):
        approval.revoke(source.tenant, row['run_id'], row['approval_id'], 'reviewer:late')
    assert store.get(source.tenant, lease.run_id)['tool_calls'] == 1


@pytest.mark.parametrize('patch', [
    {'arguments_sha256': 'b'*64}, {'input_ref': str(uuid4())}, {'resource_ref': 'visitor:other'},
    {'version': 'ticket-v2'},
])
def test_installed_approval_cannot_be_consumed_by_changed_write_intent(store, patch):
    source, approval, row, lease, intent = setup(store)
    with pytest.raises(ActionDenied):
        reserve(store, lease, intent | patch)
    assert approval.get(source.tenant, row['run_id'])['status'] == 'APPROVED'
    assert store.get(source.tenant, lease.run_id)['tool_calls'] == 0


def test_expiry_or_revocation_wins_before_step_reservation(store):
    source, approval, row, lease, intent = setup(store)
    elapsed(row)
    with pytest.raises(ActionDenied):
        reserve(store, lease, intent)
    approval.expire(source.tenant, row['run_id'], row['approval_id'])
    with pytest.raises(LostLease):
        reserve(store, lease, intent)
    assert not any(step['effect'] == 'WRITE' for step in store.steps(source.tenant, lease.run_id))


def test_write_step_and_budget_roll_back_if_approval_consumption_fails(store):
    source, approval, row, lease, intent = setup(store)
    with admin() as connection:
        connection.execute("REVOKE UPDATE(consumed_at) ON agent_approval FROM supportflow_agent_runtime")
    try:
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            reserve(store, lease, intent)
    finally:
        with admin() as connection:
            connection.execute("GRANT UPDATE(consumed_at) ON agent_approval TO supportflow_agent_runtime")
    assert approval.get(source.tenant, row['run_id'])['status'] == 'APPROVED'
    assert store.get(source.tenant, lease.run_id)['tool_calls'] == 0
    assert [step['effect'] for step in store.steps(source.tenant, lease.run_id)] == ['NONE']


def test_concurrent_distinct_step_reservations_consume_only_once(store):
    source, approvals, _, lease, intent = setup(store)
    start = Barrier(6)

    def contender(index):
        start.wait(timeout=10)
        try:
            return reserve(store, lease, intent, f'parallel-{index}')[0]['step_no']
        except ActionDenied:
            return None

    with ThreadPoolExecutor(max_workers=6) as pool:
        committed = [step for step in pool.map(contender, range(6)) if step is not None]
    assert len(committed) == 1 and approvals.get(source.tenant, lease.run_id)['consumed_step_no'] == committed[0]
    assert store.get(source.tenant, lease.run_id)['tool_calls'] == 1


def test_committed_write_checkpoint_can_be_loaded_by_new_lease_without_new_approval(store):
    source, approvals, row, lease, intent = setup(store)
    step, _ = reserve(store, lease, intent)
    with pytest.raises(RuntimeConflict):
        active = store.get(source.tenant, lease.run_id)
        approvals.approved_arguments(ExecutionContext(store, active, Event(), 30))
    store.complete_step(lease, step['step_key'], 'SUCCEEDED', {'business_ref': 'ticket:123'})
    store.retry(lease, 'SYNTHETIC_ACK_LOST', delay_seconds=0)
    resumed = store.claim_next(source.tenant, 'another-worker')
    assert approvals.approved_arguments(ExecutionContext(store, resumed, Event(), 30)) == {
        'title': TITLE, 'content': CONTENT, 'snapshot_id': row['snapshot_id'], 'arguments_sha256': row['arguments_sha256']}
    assert resumed['tool_calls'] == 1 and approvals.get(source.tenant, lease.run_id)['status'] == 'CONSUMED'


def test_tool_overlength_proposal_cannot_create_approval_or_checkpoint(store):
    source = request()
    run = store.enqueue(source)['run_id']
    active = store.claim_next(source.tenant, 'worker')
    with pytest.raises(ValueError, match='Tool arguments exceed limits'):
        TicketApprovals(store).pause(ExecutionContext(store, active, Event(), 30), uuid4(), TITLE, 'a'*8192)
    assert store.steps(source.tenant, run) == [] and TicketApprovals(store).get(source.tenant, run) is None
