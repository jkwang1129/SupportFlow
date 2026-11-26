"""Terminal ticket confirmation with a real AI database and a bounded HTTP fixture."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import FrozenInstanceError, replace
import os
from threading import Barrier
from uuid import uuid4

import psycopg
from psycopg.types.json import Jsonb
import pytest

from ai.runtime.reconciliation import ReplyReconciler, TicketRecoveryCandidate, TicketReconciler
from ai.runtime.result import ResultClient
from ai.runtime.ticket_creation import TicketCreationClient, TicketCreationReceipt
from ai.runtime.types import RuntimeConflict
from ai.runtime.worker import PermanentFailure, TransientFailure
from test_runtime_source import KEY
from test_runtime_store_postgres import admin, request, store
from test_ticket_write_tool_postgres import run, setup

pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),
                                reason='requires disposable runtime PostgreSQL')


def held(store, mode='cancel'):
    source, _, calls, factory = setup(store, mode)
    before = run(store, factory())
    assert (before['state'], before['error_code']) == ('FAILED', 'SIDE_EFFECT_UNKNOWN')
    steps = store.steps(source.tenant, before['run_id'])
    candidate = TicketRecoveryCandidate.from_rows(before, steps)
    return source, before, steps, candidate, calls


def receipt(candidate):
    return TicketCreationReceipt(candidate.source.tenant, candidate.run_id, candidate.source.source_event_id,
                                 candidate.snapshot_id, candidate.arguments_sha256, 123, 'TK_123')


class ReceiptFixture(TicketCreationClient):
    def __init__(self, candidate, calls, mode='normal', before_get=None):
        super().__init__('http://customer.test', KEY)
        self.candidate, self.calls, self.mode, self.before_get = candidate, calls, mode, before_get

    async def create(self, *_):
        pytest.fail('Terminal confirmation attempted ticket creation')

    async def receipt(self, source, run_id, *, expected_snapshot_id, expected_hash):
        self.calls['gets'] += 1
        assert (source, run_id, expected_snapshot_id, expected_hash) == (
            self.candidate.source, self.candidate.run_id, self.candidate.snapshot_id, self.candidate.arguments_sha256)
        if self.before_get:
            self.before_get()
        if self.mode == 'down':
            raise TransientFailure('TICKET_CREATE_UNAVAILABLE')
        if self.mode == 'absent':
            return None
        result = receipt(self.candidate)
        if self.mode == 'mismatched':
            return replace(result, snapshot_id=uuid4())
        return result


def reconcile(store, candidate, calls, **kwargs):
    sdk = ReceiptFixture(candidate, calls, **kwargs)
    return TicketReconciler(store, sdk, 'operator:ticket-recovery').reconcile(candidate.source.tenant, candidate.run_id)


def test_confirms_historical_ticket_and_unblocks_next_event_without_restoring_execution(store):
    source, before, steps, candidate, calls = held(store)
    pending = store.enqueue(request(seq=2))
    assert store.claim_next(source.tenant, 'next-worker') is None
    with pytest.raises(FrozenInstanceError):
        candidate.operation = 'session.reply'
    audit = reconcile(store, candidate, calls)
    after = store.get(source.tenant, candidate.run_id)
    final_steps = store.steps(source.tenant, candidate.run_id)
    assert after == before | dict(error_code='SIDE_EFFECT_CONFIRMED', fence=before['fence'] + 1,
                                  result_ref=final_steps[-1]['result_ref'], updated_at=after['updated_at'])
    assert final_steps[:-1] == steps[:-1]
    assert final_steps[-1] == steps[-1] | dict(state='SUCCEEDED', result_ref=after['result_ref'])
    assert audit['original_step_result'] == steps[-1]['result_ref']
    assert audit['operation'] == 'ticket.create' and audit['ticket_id'] == 123
    assert audit['operator_ref'] == 'operator:ticket-recovery'
    assert reconcile(store, candidate, calls) == audit
    assert len(calls['posts']) == calls['gets'] == 1
    assert store.reply_reconciliation(source.tenant, candidate.run_id) is None
    assert store.ticket_reconciliation('TENANT_B', candidate.run_id) is None
    assert store.claim_next(source.tenant, 'next-worker')['run_id'] == pending['run_id']


@pytest.mark.parametrize('mode', ['absent', 'down', 'mismatched'])
def test_missing_unavailable_or_wrong_receipt_preserves_hold(store, mode):
    source, before, steps, candidate, calls = held(store)
    if mode == 'absent':
        assert reconcile(store, candidate, calls, mode=mode) is None
    else:
        with pytest.raises(TransientFailure if mode == 'down' else PermanentFailure):
            reconcile(store, candidate, calls, mode=mode)
    assert store.ticket_reconciliation(source.tenant, candidate.run_id) is None
    assert store.get(source.tenant, candidate.run_id) == before
    assert store.steps(source.tenant, candidate.run_id) == steps
    assert len(calls['posts']) == calls['gets'] == 1
    store.enqueue(request(seq=2))
    assert store.claim_next(source.tenant, 'next-worker') is None


def test_foreign_live_and_reply_recoveries_stop_before_ticket_receipt_io(store):
    source, _, _, candidate, calls = held(store)
    sdk = ReceiptFixture(candidate, calls)
    reconciler = TicketReconciler(store, sdk, 'operator:recovery')
    with pytest.raises(RuntimeConflict):
        reconciler.reconcile('TENANT_B', candidate.run_id)
    live = store.enqueue(request(session='live-other'))
    store.claim_next(source.tenant, 'other-worker')
    with pytest.raises(RuntimeConflict):
        reconciler.reconcile(source.tenant, live['run_id'])
    with pytest.raises(ValueError):
        store.confirm_reply_commit(candidate, receipt(candidate), 'operator:wrong-operation')
    with pytest.raises(RuntimeConflict):
        ReplyReconciler(store, ResultClient('http://customer.test', KEY),
                        'operator:reply').reconcile(source.tenant, candidate.run_id)
    assert calls['gets'] == 0


@pytest.mark.parametrize('field,value', [
    ('tenant', 'TENANT_B'), ('event_id', uuid4()), ('run_id', uuid4()), ('snapshot_id', uuid4()),
    ('arguments_sha256', 'b' * 64), ('ticket_id', True), ('ticket_no', '../wrong'),
])
def test_typed_but_mismatched_ticket_receipts_cannot_confirm(store, field, value):
    source, before, _, candidate, _ = held(store)
    with pytest.raises(PermanentFailure, match='TICKET_CREATE_RECEIPT_MISMATCH'):
        store.confirm_ticket_commit(candidate, replace(receipt(candidate), **{field: value}), 'operator:recovery')
    assert store.get(source.tenant, candidate.run_id) == before


def test_fence_change_during_receipt_get_is_rejected_after_acquiring_lock(store):
    source, before, _, candidate, calls = held(store)

    def change_fence():
        with admin() as connection:
            connection.execute("SET lock_timeout='500ms'")
            connection.execute('UPDATE agent_run SET fence=fence+1 WHERE run_id=%s', (candidate.run_id,))

    with pytest.raises(RuntimeConflict, match='stale'):
        reconcile(store, candidate, calls, before_get=change_fence)
    assert store.get(source.tenant, candidate.run_id) == before | {'fence': before['fence'] + 1}
    assert store.ticket_reconciliation(source.tenant, candidate.run_id) is None


@pytest.mark.parametrize('fault', ['multiple', 'operation', 'hash'])
def test_ambiguous_or_corrupt_step_is_rejected_before_receipt_query(store, fault):
    source, _, _, candidate, calls = held(store)
    with admin() as connection:
        if fault == 'multiple':
            connection.execute("""INSERT INTO agent_step(tenant_code,run_id,step_no,step_key,kind,effect,intent_hash,intent_ref,fence,state,result_ref)
                SELECT tenant_code,run_id,step_no+1,'extra-write',kind,effect,intent_hash,intent_ref,fence,state,result_ref
                FROM agent_step WHERE run_id=%s AND effect='WRITE'""", (candidate.run_id,))
        elif fault == 'operation':
            connection.execute("UPDATE agent_step SET intent_ref=jsonb_set(intent_ref,'{operation}',%s) WHERE run_id=%s AND effect='WRITE'",
                               (Jsonb('session.reply'), candidate.run_id))
        else:
            connection.execute(
                "UPDATE agent_step SET intent_hash=%s WHERE run_id=%s AND effect='WRITE'", ('d' * 64, candidate.run_id))
    with pytest.raises(RuntimeConflict):
        reconcile(store, candidate, calls)
    assert calls['gets'] == 0 and store.ticket_reconciliation(source.tenant, candidate.run_id) is None


def test_concurrent_confirmations_share_one_audit_and_conflicting_proof_is_rejected(store):
    source, before, _, candidate, _ = held(store)
    proof = receipt(candidate)
    barrier = Barrier(6)

    def confirm(index):
        barrier.wait(timeout=10)
        return store.confirm_ticket_commit(candidate, proof, f'operator:{index}')

    with ThreadPoolExecutor(max_workers=6) as pool:
        audits = list(pool.map(confirm, range(6)))
    assert all(item == audits[0] for item in audits)
    assert store.get(source.tenant, candidate.run_id)['fence'] == before['fence'] + 1
    with pytest.raises(RuntimeConflict, match='proof changed'):
        store.confirm_ticket_commit(candidate, replace(proof, ticket_id=999), 'operator:conflict')


def test_audit_and_step_rollback_on_failed_final_run_update(store):
    source, before, steps, candidate, _ = held(store)
    with admin() as connection:
        connection.execute("""CREATE FUNCTION fail_ticket_confirmation() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'synthetic final update failure'; END $$;
        CREATE TRIGGER fail_ticket_confirmation BEFORE UPDATE ON agent_run FOR EACH ROW EXECUTE FUNCTION fail_ticket_confirmation()""")
    try:
        with pytest.raises(psycopg.errors.RaiseException):
            store.confirm_ticket_commit(candidate, receipt(candidate), 'operator:recovery')
    finally:
        with admin() as connection:
            connection.execute(
                'DROP TRIGGER fail_ticket_confirmation ON agent_run; DROP FUNCTION fail_ticket_confirmation()')
    assert store.ticket_reconciliation(source.tenant, candidate.run_id) is None
    assert store.get(source.tenant, candidate.run_id) == before
    assert store.steps(source.tenant, candidate.run_id) == steps


def test_lost_confirmation_ack_and_deadline_can_be_recovered_with_only_one_receipt_get(store):
    source, _, _, candidate, calls = held(store)
    with admin() as connection:
        connection.execute(
            "UPDATE agent_run SET deadline_at=clock_timestamp()-interval '1 second' WHERE run_id=%s", (candidate.run_id,))

    class LostAck:
        def __getattr__(self, name):
            return getattr(store, name)

        def confirm_ticket_commit(self, *args):
            store.confirm_ticket_commit(*args)
            raise psycopg.OperationalError('synthetic lost audit commit acknowledgement')

    with pytest.raises(psycopg.OperationalError):
        reconcile(LostAck(), candidate, calls)
    assert reconcile(store, candidate, calls)['ticket_id'] == 123
    assert calls['gets'] == 1
    with admin() as connection:
        connection.execute("UPDATE agent_run SET result_ref='{}' WHERE run_id=%s", (candidate.run_id,))
    with pytest.raises(RuntimeConflict, match='inconsistent'):
        store.ticket_reconciliation(source.tenant, candidate.run_id)
