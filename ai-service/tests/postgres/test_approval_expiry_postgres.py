"""Discover and close expired approvals while preserving uncertain writes."""
import os

import pytest

from ai.runtime.approvals import TicketApprovals
from ai.runtime.types import Lease
from ai.runtime.worker import RuntimeWorker
from test_approval_lifecycle_postgres import decide, elapsed, wait
from test_runtime_store_postgres import admin, expire, request, store

pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),
                                reason='requires disposable runtime PostgreSQL')


def test_waiting_only_tenant_is_discovered_when_due_and_worker_releases_next_source(store):
    source, before, approvals, row = wait(store)
    later = store.enqueue(request(seq=2))
    assert store.due_tenants() == []
    elapsed(row)
    assert store.due_tenants() == [source.tenant]
    executed = []
    worker = RuntimeWorker(
        store, {'runtime-v1': lambda ctx: executed.append(ctx.lease.run_id) or {'outcome': 'handled'}}, 'expiry-worker')
    assert worker.tick()['run_id'] == later['run_id'] and executed == [later['run_id']]
    closed = store.get(source.tenant, row['run_id'])
    assert (closed['state'], closed['error_code'], closed['fence']) == (
        'FAILED', 'APPROVAL_EXPIRED', before['fence'] + 1)
    for field in ('step_count', 'tool_calls', 'model_calls', 'retry_count', 'deadline_at'):
        assert closed[field] == before[field]
    assert approvals.get(source.tenant, row['run_id'])['status'] == 'EXPIRED'


def test_expired_approved_checkpoint_is_closed_before_worker_enters_handler(store):
    source, _, approvals, row = wait(store)
    decide(approvals, source, row)
    elapsed(row)
    worker = RuntimeWorker(
        store, {'runtime-v1': lambda _: pytest.fail('Expired approval reached business handler')}, 'expiry-worker')
    assert worker.tick() is None
    assert store.get(source.tenant, row['run_id'])['error_code'] == 'APPROVAL_EXPIRED'
    assert approvals.get(source.tenant, row['run_id'])['decided_by'] == 'reviewer:1'
    assert store.due_tenants() == []


def test_scheduler_skips_locked_expiry_and_keeps_active_waits(store):
    source, _, approvals, row = wait(store)
    assert approvals.expire_due(source.tenant) == 0
    elapsed(row)
    with store._scope(source.tenant) as connection:
        connection.execute('SELECT run_id FROM agent_run WHERE run_id=%s FOR UPDATE', (row['run_id'],)).fetchone()
        assert approvals.expire_due(source.tenant) == 0
    assert approvals.expire_due(source.tenant) == 1
    assert approvals.expire_due(source.tenant) == 0


def test_expiry_is_tenant_scoped_and_does_not_recover_uncertain_writes(store):
    source, _, approvals, row = wait(store)
    elapsed(row)
    assert TicketApprovals(store).expire_due('TENANT_B') == 0
    with admin() as connection:
        # An old held write may never be cleared by approval housekeeping.
        connection.execute("""INSERT INTO agent_step(tenant_code,run_id,step_no,step_key,kind,effect,intent_hash,intent_ref,state,fence)
            SELECT tenant_code,run_id,2,'held-write','TOOL','WRITE',repeat('a',64),'{}','UNKNOWN',fence
            FROM agent_run WHERE run_id=%s""", (row['run_id'],))
    assert approvals.expire_due(source.tenant) == 0 and store.due_tenants() == []
    assert approvals.get(source.tenant, row['run_id'])['status'] == 'PENDING'
    assert store.get(source.tenant, row['run_id'])['state'] == 'WAITING_APPROVAL'


def test_read_only_worker_skips_approved_run_without_blocking_other_sessions(store):
    source, _, approvals, row = wait(store)
    decide(approvals, source, row)
    approved = store.get(source.tenant, row['run_id'])
    later = store.enqueue(request(seq=2))
    independent = store.enqueue(request(session='INDEPENDENT'))
    seen = []
    worker = RuntimeWorker(store, {'runtime-v1': lambda ctx: seen.append(ctx.lease.run_id) or {'outcome': 'read'}},
                           'read-only', tenant=source.tenant, approval_mode='without_approval')
    assert worker.tick()['run_id'] == independent['run_id']
    assert worker.tick() is None and seen == [independent['run_id']]
    assert store.get(source.tenant, row['run_id']) == approved
    assert store.get(source.tenant, later['run_id']) == later
    assert store.claim_next(source.tenant, 'trusted-approval-host')['run_id'] == row['run_id']


def test_read_only_worker_cannot_take_over_an_expired_approval_execution_lease(store):
    source, _, approvals, row = wait(store)
    decide(approvals, source, row)
    lease = Lease.from_run(store.claim_next(source.tenant, 'trusted-old-owner'))
    expire(lease)
    before = store.get(source.tenant, row['run_id'])
    readonly = RuntimeWorker(store, {'runtime-v1': lambda _: pytest.fail('Approval reached the read-only worker')},
                             'read-only', tenant=source.tenant, approval_mode='without_approval')
    assert readonly.tick() is None
    assert store.get(source.tenant, row['run_id']) == before
    recovered = store.claim_next(source.tenant, 'trusted-next-owner')
    assert recovered['run_id'] == row['run_id'] and recovered['fence'] > lease.fence


def test_approval_worker_ignores_pending_read_work_and_unapproved_proposal(store):
    source, waiting, approvals, row = wait(store)
    ordinary = store.enqueue(request(session='ORDINARY'))
    seen = []
    worker = RuntimeWorker(store, {'runtime-v1': lambda ctx: seen.append(ctx.lease.run_id) or {'outcome': 'preview'}},
                           'approval-only', tenant=source.tenant, approval_mode='approved_only')
    assert worker.tick() is None
    assert store.get(source.tenant, row['run_id']) == waiting
    decide(approvals, source, row)
    assert worker.tick()['run_id'] == row['run_id'] and seen == [row['run_id']]
    assert store.get(source.tenant, ordinary['run_id']) == ordinary
