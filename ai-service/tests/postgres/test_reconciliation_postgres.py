"""Terminal recovery with restricted PG; business HTTP is a protocol Stub here."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import os
from threading import Barrier
from uuid import uuid4

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
import pytest

from ai.runtime.reconciliation import ReplyRecoveryCandidate, ReplyReconciler
from ai.runtime.result import ReplyReceipt
from ai.runtime.types import RuntimeConflict
from ai.runtime.worker import PermanentFailure, TransientFailure
from test_publication_postgres import setup, worker
from test_runtime_store_postgres import store, admin, request

pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),
                                reason='requires disposable runtime PostgreSQL')


def held(store, mode='cancel'):
    source, row, handler, calls, posted, committed, reads, models, state = setup(store, mode)
    result = worker(store, handler).run_once(source.tenant)
    assert result['state'] == 'FAILED' and result['error_code'] == 'SIDE_EFFECT_UNKNOWN'
    return source, result, handler, calls


def observation(store, source, row):
    return ReplyRecoveryCandidate.from_rows(store.get(source.tenant, row['run_id']), store.steps(source.tenant, row['run_id']))


def proof(candidate):
    return ReplyReceipt(candidate.run_id, candidate.source.source_event_id, candidate.snapshot_id, candidate.arguments_sha256, 123, 'BOT_MSG_1', 2)


def count():
    with admin() as db:
        return db.execute('SELECT count(*) AS n FROM agent_reply_reconciliation').fetchone()['n']


def test_cancelled_terminal_reply_confirmed_without_restoring_execution_or_changing_history_budget(store):
    source, before, handler, calls = held(store)
    next_run = store.enqueue(request(seq=2))
    assert store.claim_next(source.tenant, 'next-worker') is None
    before_step = [s for s in store.steps(source.tenant, before['run_id']) if s['effect'] == 'WRITE'][0]
    reconciler = ReplyReconciler(store, handler.results, 'operator:local-recovery')
    audit = reconciler.reconcile(source.tenant, before['run_id'])
    after = store.get(source.tenant, before['run_id'])
    after_step = [s for s in store.steps(source.tenant, before['run_id']) if s['effect'] == 'WRITE'][0]
    assert after['state'] == 'FAILED' and after['error_code'] == 'SIDE_EFFECT_CONFIRMED' and after['cancel_requested'] is True
    for field in ('step_count', 'tool_calls', 'model_calls', 'retry_count', 'attempt', 'finished_at', 'deadline_at', 'cancel_requested'):
        assert after[field] == before[field]
    assert after['fence'] == before['fence'] + 1 and after['owner'] is None and after['lease_until'] is None
    assert after_step['state'] == 'SUCCEEDED' and after_step['completed_at'] == before_step['completed_at']
    assert audit['original_step_result'] == before_step['result_ref'] and audit['original_error_code'] == 'SIDE_EFFECT_UNKNOWN'
    assert audit['observed_fence'] == before['fence'] and audit['operator_ref'] == 'operator:local-recovery'
    assert calls == ['POST', 'GET'] and count() == 1 and after['result_ref'] == after_step['result_ref']
    assert ReplyReconciler(store, handler.results, 'operator:second').reconcile(
        source.tenant, before['run_id']) == audit
    assert calls == ['POST', 'GET'] and store.get(source.tenant, before['run_id']) == after
    assert store.claim_next(source.tenant, 'next-worker')['run_id'] == next_run['run_id']


def test_absence_does_not_change_hold_or_repost_and_following_event_remains_blocked(store):
    source, before, handler, calls = held(store, 'absent')
    assert ReplyReconciler(store, handler.results, 'operator:recovery').reconcile(
        source.tenant, before['run_id']) is None
    assert store.get(source.tenant, before['run_id']) == before and count(
    ) == 0 and calls == ['POST', 'GET', 'GET']
    store.enqueue(request(seq=2))
    assert store.claim_next(source.tenant, 'next-worker') is None


def test_lookup_outage_preserves_unknown_without_extra_post(store):
    source, before, handler, calls = held(store, 'lookup_down')
    with pytest.raises(TransientFailure):
        ReplyReconciler(store, handler.results, 'operator:recovery').reconcile(source.tenant, before['run_id'])
    assert store.get(source.tenant, before['run_id']) == before and count(
    ) == 0 and calls == ['POST', 'GET', 'GET']


@pytest.mark.parametrize('field,value', [('run_id', uuid4()), ('event_id', uuid4()), ('snapshot_id', uuid4()),
                                         ('arguments_sha256', 'b' * 64), ('message_id', True), ('sequence', True), ('message_no', 'secret / unsafe')])
def test_typed_but_mismatched_receipt_cannot_resolve_unknown(store, field, value):
    source, before, handler, calls = held(store)
    candidate = observation(store, source, before)
    with pytest.raises(PermanentFailure, match='REPLY_RECEIPT_MISMATCH'):
        store.confirm_reply_commit(candidate, replace(proof(candidate), **{field: value}), 'operator:recovery')
    assert store.get(source.tenant, before['run_id']) == before and count() == 0


def test_wrong_tenant_or_live_or_succeeded_run_never_calls_business_receipt(store):
    source, before, handler, calls = held(store)
    with pytest.raises(RuntimeConflict):
        ReplyReconciler(store, handler.results, 'operator:recovery').reconcile('TENANT_B', before['run_id'])
    live = store.enqueue(request(session='LIVE'))
    store.claim_next(source.tenant, 'other-worker')
    with pytest.raises(RuntimeConflict):
        ReplyReconciler(store, handler.results, 'operator:recovery').reconcile(source.tenant, live['run_id'])
    assert calls == ['POST'] and count() == 0


def test_stale_fence_and_changed_intent_are_rejected_before_atomic_confirmation(store):
    source, before, handler, calls = held(store)
    candidate = observation(store, source, before)
    with admin() as db:
        db.execute('UPDATE agent_run SET fence=fence+1 WHERE run_id=%s', (before['run_id'],))
    with pytest.raises(RuntimeConflict, match='stale'):
        store.confirm_reply_commit(candidate, proof(candidate), 'operator:recovery')
    with admin() as db:
        db.execute("UPDATE agent_step SET intent_hash=%s WHERE effect='WRITE'", ('b' * 64,))
    with pytest.raises(RuntimeConflict):
        ReplyReconciler(store, handler.results, 'operator:recovery').reconcile(source.tenant, before['run_id'])
    assert count() == 0 and calls == ['POST']


def test_multiple_writes_require_explicit_business_specific_recovery_instead_of_assumption(store):
    source, before, handler, calls = held(store)
    with admin() as db:
        db.execute("""INSERT INTO agent_step(tenant_code,run_id,step_no,step_key,kind,effect,intent_hash,intent_ref,fence,state,result_ref)
            SELECT tenant_code,run_id,step_no+1,'another-write',kind,effect,intent_hash,intent_ref,fence,state,result_ref
            FROM agent_step WHERE effect='WRITE'""")
    with pytest.raises(RuntimeConflict, match='exactly one'):
        ReplyReconciler(store, handler.results, 'operator:recovery').reconcile(source.tenant, before['run_id'])
    assert count() == 0 and calls == ['POST']


def test_parallel_same_proof_has_one_immutable_audit_and_one_fence_increment(store):
    source, before, handler, calls = held(store)
    candidate = observation(store, source, before)
    receipt = proof(candidate)
    barrier = Barrier(6)

    def confirm(index):
        barrier.wait(timeout=10)
        return store.confirm_reply_commit(candidate, receipt, 'operator:' + str(index))
    with ThreadPoolExecutor(6) as pool:
        audits = list(pool.map(confirm, range(6)))
    assert all(audit == audits[0] for audit in audits) and count() == 1
    assert store.get(source.tenant, before['run_id'])['fence'] == before['fence'] + 1
    with pytest.raises(RuntimeConflict, match='proof changed'):
        store.confirm_reply_commit(candidate, replace(receipt, message_id=999), 'operator:changed')
    with pytest.raises(RuntimeConflict, match='proof changed'):
        store.confirm_reply_commit(replace(candidate, step_key='different-step'), receipt, 'operator:changed')


def test_audit_and_step_changes_roll_back_when_final_run_update_fails(store):
    source, before, handler, calls = held(store)
    candidate = observation(store, source, before)
    with admin() as db:
        db.execute("""CREATE FUNCTION reject_reconciliation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
            IF NEW.error_code='SIDE_EFFECT_CONFIRMED' THEN RAISE EXCEPTION 'synthetic reconciliation failure'; END IF; RETURN NEW; END $$""")
        db.execute(
            'CREATE TRIGGER fail_reconciliation BEFORE UPDATE ON agent_run FOR EACH ROW EXECUTE FUNCTION reject_reconciliation()')
    try:
        with pytest.raises(psycopg.errors.RaiseException):
            store.confirm_reply_commit(candidate, proof(candidate), 'operator:recovery')
        assert store.get(source.tenant, before['run_id']) == before and count() == 0
        assert any(s['effect'] == 'WRITE' and s['state'] ==
                   'UNKNOWN' for s in store.steps(source.tenant, before['run_id']))
    finally:
        with admin() as db:
            db.execute('DROP TRIGGER fail_reconciliation ON agent_run; DROP FUNCTION reject_reconciliation()')


def test_recovery_after_deadline_and_lost_confirmation_ack_does_not_query_or_write_again(store):
    source, before, handler, calls = held(store)
    with admin() as db:
        db.execute(
            "UPDATE agent_run SET deadline_at=clock_timestamp()-interval '1 second' WHERE run_id=%s", (before['run_id'],))

    class LostAck:
        def __getattr__(self, name): return getattr(store, name)

        def confirm_reply_commit(self, *args):
            store.confirm_reply_commit(*args)
            raise psycopg.OperationalError('synthetic lost recovery commit acknowledgement')
    with pytest.raises(psycopg.OperationalError):
        ReplyReconciler(LostAck(), handler.results, 'operator:first').reconcile(source.tenant, before['run_id'])
    audit = ReplyReconciler(store, handler.results, 'operator:retry').reconcile(source.tenant, before['run_id'])
    assert audit['operator_ref'] == 'operator:first' and calls == ['POST', 'GET'] and count() == 1
    with admin() as db:
        db.execute("UPDATE agent_run SET result_ref='{}' WHERE run_id=%s", (before['run_id'],))
    with pytest.raises(RuntimeConflict, match='inconsistent'):
        store.reply_reconciliation(source.tenant, before['run_id'])


def test_audit_is_tenant_scoped_append_only_and_readiness_rejects_permission_drift(store, monkeypatch):
    source, before, handler, calls = held(store)
    audit = ReplyReconciler(store, handler.results, 'operator:recovery').reconcile(source.tenant, before['run_id'])
    assert store.reply_reconciliation('TENANT_B', before['run_id']) is None
    with store.connector() as db:
        db.autocommit = True
        assert db.execute('SELECT * FROM agent_reply_reconciliation').fetchall() == []
        for sql in ('UPDATE agent_reply_reconciliation SET operator_ref=\'tamper\'', 'DELETE FROM agent_reply_reconciliation',
                    'TRUNCATE agent_reply_reconciliation', 'ALTER TABLE agent_reply_reconciliation DISABLE ROW LEVEL SECURITY'):
            with pytest.raises(psycopg.errors.InsufficientPrivilege):
                db.execute(sql)
        db.execute("SELECT set_config('app.tenant_code','PLATFORM',false)")
        assert db.execute('SELECT * FROM agent_reply_reconciliation').fetchall() == []
        db.execute("SELECT set_config('app.tenant_code',%s,false)", (source.tenant,))
        columns = [key for key in audit if key != 'confirmed_at']
        values = {key: Jsonb(value) if key == 'original_step_result' else value for key,
                  value in audit.items() if key != 'confirmed_at'}
        from psycopg import sql
        insert = sql.SQL('INSERT INTO agent_reply_reconciliation ({}) VALUES ({})').format(
            sql.SQL(',').join(map(sql.Identifier, columns)), sql.SQL(',').join(sql.Placeholder() * len(columns)))
        values['run_id'] = uuid4()
        with pytest.raises(psycopg.errors.ForeignKeyViolation):
            db.execute(insert, [values[key] for key in columns])
        values['tenant_code'] = 'TENANT_B'
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            db.execute(insert, [values[key] for key in columns])
    with admin() as db:
        db.execute('CREATE POLICY extra_recovery_allow ON agent_reply_reconciliation USING(true) WITH CHECK(true)')
    try:
        assert store.reply_reconciliation('TENANT_B', before['run_id']) is None
    finally:
        with admin() as db:
            db.execute('DROP POLICY extra_recovery_allow ON agent_reply_reconciliation')
    from ai.core import schema_guard
    def connector(
        **kwargs): return psycopg.connect(os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'], row_factory=dict_row, **kwargs)
    monkeypatch.setattr(schema_guard, 'connect', connector)
    assert schema_guard.runtime_missing() == {}
    with admin() as db:
        db.execute('GRANT UPDATE ON agent_reply_reconciliation TO supportflow_agent_runtime')
    try:
        assert 'ai_db.agent_reply_reconciliation.runtime_permissions' in schema_guard.runtime_missing()
    finally:
        with admin() as db:
            db.execute('REVOKE UPDATE ON agent_reply_reconciliation FROM supportflow_agent_runtime')


@pytest.mark.parametrize('field,value', [('fence', True), ('step_no', True), ('arguments_sha256', 'wrong'), ('run_id', 'not-a-uuid')])
def test_forged_recovery_observation_has_strict_types(store, field, value):
    source, before, handler, calls = held(store)
    candidate = observation(store, source, before)
    with pytest.raises(ValueError):
        replace(candidate, **{field: value})
