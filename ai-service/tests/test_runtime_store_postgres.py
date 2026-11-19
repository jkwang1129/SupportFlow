"""Real restricted-role PostgreSQL; test script supplies only disposable credentials."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import os
from threading import Barrier
from uuid import uuid4

import psycopg
from psycopg.rows import dict_row
import pytest

from ai.runtime.store import RuntimeStore
from ai.runtime.types import RunInput, Limits, Lease, RuntimeConflict, LostLease, BudgetExceeded, QueueFull

pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),reason='requires disposable runtime PostgreSQL')


def admin():
    return psycopg.connect(os.environ['SUPPORTFLOW_RUNTIME_ADMIN_TEST_DSN'],autocommit=True,row_factory=dict_row)


@pytest.fixture
def store():
    with admin() as connection:
        connection.execute('TRUNCATE public.agent_step,public.agent_run')
    return RuntimeStore(lambda: psycopg.connect(os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'],row_factory=dict_row))


def request(tenant='TENANT_A',session='S1',seq=1):
    return RunInput(tenant,uuid4(),session,seq,seq,'visitor:42','trace-1')


def acquire(store,tenant='TENANT_A',owner='worker-1'):
    row=store.claim_next(tenant,owner)
    assert row is not None
    return Lease.from_run(row)


def expire(lease, *, deadline=False):
    with admin() as connection:
        column='deadline_at' if deadline else 'lease_until'
        connection.execute(f"UPDATE agent_run SET {column}=clock_timestamp()-interval '1 second' WHERE tenant_code=%s AND run_id=%s",(lease.tenant,lease.run_id))


def test_duplicate_input_is_stable_and_changed_subject_or_budget_conflicts(store):
    input=request()
    row=store.enqueue(input)
    assert store.enqueue(input)['run_id']==row['run_id']
    for changed,limits in [(replace(input,principal_ref='visitor:99'),Limits()),(input,Limits(max_steps=1))]:
        with pytest.raises(RuntimeConflict): store.enqueue(changed,limits)
    assert store.get('TENANT_B',row['run_id']) is None


def test_concurrent_duplicate_events_create_one_run(store):
    input=request(); barrier=Barrier(8)
    def submit(_):
        barrier.wait(timeout=10)
        return store.enqueue(input)['run_id']
    with ThreadPoolExecutor(8) as pool:
        results=list(pool.map(submit,range(8)))
    assert len(set(results))==1


def test_queue_bound_is_per_tenant_and_duplicate_does_not_consume_capacity(store):
    bounded=RuntimeStore(store.connector,backlog_limit=2)
    input=request(); bounded.enqueue(input); bounded.enqueue(request(session='S2'))
    assert bounded.enqueue(input)
    with pytest.raises(QueueFull): bounded.enqueue(request(session='S3'))
    assert bounded.enqueue(request('TENANT_B'))
    assert set(store.due_tenants())=={'TENANT_A','TENANT_B'}
    assert len(store.due_tenants(1))==1


def test_monotonic_source_order_and_one_active_session(store):
    first=store.enqueue(request(seq=2))
    with pytest.raises(RuntimeConflict): store.enqueue(request(seq=1))
    second=store.enqueue(request(seq=3))
    lease=acquire(store)
    assert lease.run_id==first['run_id']
    assert store.claim_next('TENANT_A','worker-2') is None
    store.finish(lease,{'outcome':'complete'})
    assert acquire(store,owner='worker-2').run_id==second['run_id']


def test_blocked_session_backlog_does_not_hide_other_sessions(store):
    for seq in range(1,41): store.enqueue(request(seq=seq))
    acquire(store)
    independent=store.enqueue(request(session='INDEPENDENT'))
    assert acquire(store,owner='worker-2').run_id==independent['run_id']


def test_competing_workers_have_one_claim_winner(store):
    store.enqueue(request()); barrier=Barrier(8)
    def claim(i):
        barrier.wait(timeout=10)
        return store.claim_next('TENANT_A','worker-'+str(i))
    with ThreadPoolExecutor(8) as pool:
        results=list(pool.map(claim,range(8)))
    assert len([row for row in results if row])==1


def test_different_sessions_can_hold_concurrent_leases(store):
    for i in range(8): store.enqueue(request(session='S'+str(i)))
    barrier=Barrier(8)
    def claim(i):
        barrier.wait(timeout=10)
        return store.claim_next('TENANT_A','worker-'+str(i))
    with ThreadPoolExecutor(8) as pool:
        results=list(pool.map(claim,range(8)))
    assert len({row['run_id'] for row in results if row})==8


def test_expired_takeover_fences_every_old_write_and_preserves_unknown_intent(store):
    row=store.enqueue(request()); old=acquire(store)
    store.reserve_step(old,'model-1','MODEL','NONE',{'operation':'answer'})
    expire(old)
    current=acquire(store,owner='worker-2')
    assert current.fence>old.fence
    now=store.get('TENANT_A',row['run_id'])
    assert (now['attempt'],now['retry_count'],now['model_calls'])==(2,1,1)
    calls=[lambda:store.heartbeat(old),lambda:store.finish(old,{}),lambda:store.fail(old,'ERROR'),
           lambda:store.retry(old,'TEMPORARY'),lambda:store.reserve_step(old,'late','MODEL','NONE',{}),
           lambda:store.complete_step(old,'model-1','SUCCEEDED',{})]
    for operation in calls:
        with pytest.raises(LostLease): operation()
    step,created=store.reserve_step(current,'model-1','MODEL','NONE',{'operation':'answer'})
    assert not created and step['state']=='UNKNOWN'
    with pytest.raises(LostLease): store.complete_step(current,'model-1','SUCCEEDED',{})


def test_budget_is_not_reset_after_crash_or_store_recreation(store):
    row=store.enqueue(request(),Limits(max_model_calls=1))
    old=acquire(store); store.reserve_step(old,'model-1','MODEL','NONE',{})
    expire(old)
    recreated=RuntimeStore(store.connector); current=acquire(recreated,owner='new-process')
    with pytest.raises(BudgetExceeded): recreated.reserve_step(current,'model-2','MODEL','NONE',{})
    result=store.get('TENANT_A',row['run_id'])
    assert (result['state'],result['error_code'],result['model_calls'],result['step_count'])==('FAILED','BUDGET_EXHAUSTED',1,1)


def test_unknown_write_blocks_recovery_and_following_session_work(store):
    row=store.enqueue(request()); lease=acquire(store)
    store.reserve_step(lease,'write-1','TOOL','WRITE',{'operation':'create_ticket'})
    following=store.enqueue(request(seq=2))
    expire(lease)
    assert store.claim_next('TENANT_A','worker-2') is None
    assert store.get('TENANT_A',row['run_id'])['error_code']=='SIDE_EFFECT_UNKNOWN'
    assert store.get('TENANT_A',following['run_id'])['state']=='PENDING'
    assert store.due_tenants()==[]
    independent=store.enqueue(request(session='OTHER'))
    assert acquire(store,owner='worker-2').run_id==independent['run_id']


def test_cancel_before_execution_and_terminal_cancel_are_idempotent(store):
    row=store.enqueue(request())
    cancelled=store.cancel('TENANT_A',row['run_id'])
    assert cancelled['state']=='CANCELLED' and cancelled['cancel_requested']
    assert store.cancel('TENANT_A',row['run_id'])['updated_at']==cancelled['updated_at']
    assert store.claim_next('TENANT_A','worker') is None
    assert store.steps('TENANT_A',row['run_id'])==[]


def test_cancel_does_not_claim_that_an_unknown_write_was_undone(store):
    row=store.enqueue(request()); lease=acquire(store)
    store.reserve_step(lease,'write-1','TOOL','WRITE',{'operation':'create_ticket'})
    result=store.cancel('TENANT_A',row['run_id'])
    assert result['state']=='FAILED' and result['error_code']=='SIDE_EFFECT_UNKNOWN' and result['cancel_requested']
    with pytest.raises(LostLease): store.finish(lease,{})


def test_cancel_and_finish_race_has_one_immutable_terminal_state(store):
    for i in range(10):
        row=store.enqueue(request(session='RACE'+str(i))); lease=acquire(store)
        barrier=Barrier(2)
        def finish():
            barrier.wait(timeout=10)
            try: return store.finish(lease,{'outcome':'done'})['state']
            except LostLease: return 'STALE'
        def cancel():
            barrier.wait(timeout=10)
            return store.cancel('TENANT_A',row['run_id'])['state']
        with ThreadPoolExecutor(2) as pool:
            a=pool.submit(finish); b=pool.submit(cancel)
            outcomes=(a.result(timeout=10),b.result(timeout=10))
        result=store.get('TENANT_A',row['run_id'])
        assert result['state'] in ('SUCCEEDED','CANCELLED')
        assert (result['state']=='CANCELLED')==result['cancel_requested']
        assert outcomes in [('SUCCEEDED','SUCCEEDED'),('STALE','CANCELLED')]


def test_heartbeat_and_deadline_never_revive_an_expired_lease(store):
    row=store.enqueue(request()); lease=acquire(store)
    assert store.heartbeat(lease)['fence']==lease.fence
    expire(lease,deadline=True)
    with pytest.raises(LostLease): store.heartbeat(lease)
    with pytest.raises(LostLease): store.finish(lease,{})
    assert store.claim_next('TENANT_A','worker-2') is None
    assert store.get('TENANT_A',row['run_id'])['error_code']=='DEADLINE_EXCEEDED'


def test_retry_budget_is_shared_with_takeover_and_not_charged_twice(store):
    row=store.enqueue(request(),Limits(max_retries=1)); lease=acquire(store)
    waiting=store.retry(lease,'MODEL_429',delay_seconds=0)
    assert waiting['state']=='RETRY_WAIT' and waiting['retry_count']==1
    lease=acquire(store,owner='worker-2')
    assert store.get('TENANT_A',row['run_id'])['retry_count']==1
    result=store.retry(lease,'MODEL_429',delay_seconds=0)
    assert result['state']=='FAILED' and result['error_code']=='RETRY_EXHAUSTED'


def test_step_reservation_and_result_are_idempotent_but_changes_conflict(store):
    row=store.enqueue(request()); lease=acquire(store)
    first,fresh=store.reserve_step(lease,'model-1','MODEL','NONE',{'operation':'answer'})
    assert fresh
    same,fresh=store.reserve_step(lease,'model-1','MODEL','NONE',{'operation':'answer'})
    assert not fresh and same['step_no']==first['step_no']
    with pytest.raises(RuntimeConflict): store.reserve_step(lease,'model-1','MODEL','NONE',{'operation':'rewrite'})
    with pytest.raises(RuntimeConflict): store.finish(lease,{})
    result=store.complete_step(lease,'model-1','SUCCEEDED',{'resource_ref':'answer-1'})
    assert store.complete_step(lease,'model-1','SUCCEEDED',{'resource_ref':'answer-1'})['completed_at']==result['completed_at']
    with pytest.raises(RuntimeConflict): store.complete_step(lease,'model-1','FAILED',{})
    assert store.finish(lease,{'outcome':'complete'})['state']=='SUCCEEDED'
    assert store.get('TENANT_A',row['run_id'])['model_calls']==1


def test_permanent_failure_is_terminal(store):
    row=store.enqueue(request()); lease=acquire(store)
    assert store.fail(lease,'MODEL_PERMANENT')['state']=='FAILED'
    with pytest.raises(LostLease): store.finish(lease,{})
    assert store.get('TENANT_A',row['run_id'])['error_code']=='MODEL_PERMANENT'


def test_real_rls_and_runtime_permissions_remain_fail_closed(store):
    a=store.enqueue(request()); b=store.enqueue(request('TENANT_B'))
    with psycopg.connect(os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'],autocommit=True,row_factory=dict_row) as connection:
        flags=connection.execute("SELECT rolcanlogin,rolsuper,rolbypassrls,rolcreatedb,rolcreaterole FROM pg_roles WHERE rolname='supportflow_agent_runtime'").fetchone()
        assert not any(flags.values())
        assert not connection.execute("SELECT has_table_privilege(current_user,'supportflow_schema_history','INSERT,UPDATE,DELETE,TRUNCATE') AS writable").fetchone()['writable']
        assert connection.execute('SELECT run_id FROM agent_run').fetchall()==[]
        connection.execute("SELECT set_config('app.tenant_code','PLATFORM',false)")
        assert connection.execute('SELECT run_id FROM agent_run').fetchall()==[]
        connection.execute("SELECT set_config('app.tenant_code','TENANT_A',false)")
        assert [row['run_id'] for row in connection.execute('SELECT run_id FROM agent_run')]==[a['run_id']]
        for statement in ('DELETE FROM agent_run','TRUNCATE agent_run','ALTER TABLE agent_run DISABLE ROW LEVEL SECURITY','SET ROLE supportflow'):
            with pytest.raises(psycopg.errors.InsufficientPrivilege): connection.execute(statement)
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            connection.execute("INSERT INTO agent_step(tenant_code,run_id,step_no,step_key,kind,effect,intent_hash,intent_ref,fence) VALUES('TENANT_B',%s,1,'x','MODEL','NONE',%s,'{}',1)",(b['run_id'],'0'*64))
        with admin() as administrator:
            administrator.execute('CREATE POLICY runtime_test_permissive ON agent_run USING(true) WITH CHECK(true)')
            try:
                assert [row['run_id'] for row in connection.execute('SELECT run_id FROM agent_run')]==[a['run_id']]
            finally:
                administrator.execute('DROP POLICY runtime_test_permissive ON agent_run')
    assert store.get('TENANT_B',b['run_id'])
    with admin() as connection:
        connection.execute('SET ROLE supportflow_ops')
        try:
            with pytest.raises(psycopg.errors.InsufficientPrivilege): connection.execute('SELECT * FROM supportflow_agent_due_tenants(1)')
        finally:
            connection.execute('RESET ROLE')
