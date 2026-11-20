"""Atomic payload/Step commit on real restricted-role PG; no model or business writes."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import os
import time
from threading import Barrier
from uuid import UUID,uuid4

import psycopg
from psycopg.rows import dict_row
import pytest

from ai.runtime.store import RuntimeStore
from ai.runtime.types import RuntimeConflict,LostLease
from test_runtime_store_postgres import store,admin,request,acquire,expire

pytestmark=pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),reason='requires disposable runtime PostgreSQL')
VALUE={'action':{'operation':'reply','text':'synthetic private model output'},'usage':{'input':12,'output':7}}


def setup(store,kind='MODEL',effect='NONE'):
    row=store.enqueue(request());lease=acquire(store)
    store.reserve_step(lease,'payload-step',kind,effect,{'operation':'plan','version':'test-v1'})
    return row,lease


def complete(store,lease,schema='model-output-v1',value=None):
    return store.complete_payload_step(lease,'payload-step',schema,VALUE if value is None else value)


def load(store,lease,result):
    return store.payload(lease.tenant,lease.run_id,UUID(result['resource_ref'].removeprefix('payload:')))


@pytest.mark.parametrize('kind,effect,schema',[('MODEL','NONE','model-output-v1'),('TOOL','READ','tool-output-v1'),('INTERNAL','NONE','working-state-v1')])
def test_body_and_succeeded_step_are_persisted_together_without_changing_budget(store,kind,effect,schema):
    row,lease=setup(store,kind,effect);before=store.get(lease.tenant,lease.run_id)
    result=complete(store,lease,schema);value=load(store,lease,result)
    assert value.value==VALUE and value.schema==schema and value.step_no==1
    assert 'synthetic private model output' not in repr(value)
    step=store.steps(lease.tenant,lease.run_id)[0]
    assert step['state']=='SUCCEEDED' and step['result_ref']==result
    assert store.get(lease.tenant,lease.run_id)==before
    assert complete(store,lease,schema)==result and load(store,lease,result)==value
    assert store.steps(lease.tenant,lease.run_id)[0]['completed_at']==step['completed_at']
    assert store.payload('TENANT_B',lease.run_id,value.payload_id) is None
    assert store.payload(lease.tenant,uuid4(),value.payload_id) is None
    store.finish(lease,{'outcome':'done'})
    assert load(store,lease,result)==value  # Read is not a new execution grant.


def test_eight_concurrent_identical_completions_share_one_immutable_payload(store):
    _,lease=setup(store);start=Barrier(8)
    def submit(_):start.wait(timeout=5);return complete(store,lease)
    with ThreadPoolExecutor(8) as pool:results=list(pool.map(submit,range(8)))
    assert len({result['resource_ref'] for result in results})==1
    with admin() as db:assert db.execute('SELECT count(*) AS n FROM agent_payload').fetchone()['n']==1
    assert store.get(lease.tenant,lease.run_id)['model_calls']==1


def test_changed_payload_or_schema_cannot_replace_an_already_completed_step(store):
    _,lease=setup(store);result=complete(store,lease)
    with pytest.raises(RuntimeConflict):complete(store,lease,value={'action':'changed'})
    with pytest.raises(RuntimeConflict):complete(store,lease,'tool-output-v1')
    assert load(store,lease,result).value==VALUE


def test_write_tools_cannot_use_payload_persistence_as_a_business_receipt(store):
    _,lease=setup(store,'TOOL','WRITE')
    with pytest.raises(RuntimeConflict):complete(store,lease,'tool-output-v1')
    assert store.steps(lease.tenant,lease.run_id)[0]['state']=='RESERVED'
    with admin() as db:assert db.execute('SELECT count(*) AS n FROM agent_payload').fetchone()['n']==0


@pytest.mark.parametrize('state',['SUCCEEDED','FAILED','UNKNOWN'])
def test_nonreserved_steps_cannot_acquire_a_new_payload(store,state):
    _,lease=setup(store);store.complete_step(lease,'payload-step',state,{})
    with pytest.raises(RuntimeConflict):complete(store,lease)


@pytest.mark.parametrize('transition',['cancel','fail','lease','deadline'])
def test_revocation_and_expiry_reject_late_model_output_without_writing(store,transition):
    _,lease=setup(store)
    if transition=='cancel':store.cancel(lease.tenant,lease.run_id)
    if transition=='fail':store.fail(lease,'TEST_FAILURE')
    if transition=='lease':expire(lease)
    if transition=='deadline':expire(lease,deadline=True)
    with pytest.raises(LostLease):complete(store,lease)
    with admin() as db:assert db.execute('SELECT count(*) AS n FROM agent_payload').fetchone()['n']==0
    assert store.get(lease.tenant,lease.run_id)['model_calls']==1


def test_takeover_cannot_relabel_old_unknown_step_output_as_current_fence(store):
    _,old=setup(store);expire(old);current=acquire(store,owner='replacement-worker')
    for lease in (old,current):
        with pytest.raises(LostLease):complete(store,lease)
    assert store.steps(old.tenant,old.run_id)[0]['state']=='UNKNOWN'


def test_expiry_while_waiting_for_step_lock_cannot_commit_a_payload(store):
    _,lease=setup(store)
    observer=RuntimeStore(lambda:psycopg.connect(os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'],row_factory=dict_row,application_name='payload-lock-test'))
    with ThreadPoolExecutor(1) as pool:
        with admin() as blocker:
            blocker.execute("UPDATE agent_run SET lease_until=clock_timestamp()+interval '900 milliseconds' WHERE run_id=%s",(lease.run_id,))
            started=time.monotonic()
            with blocker.transaction():
                blocker.execute('SELECT 1 FROM agent_step WHERE run_id=%s FOR UPDATE',(lease.run_id,))
                future=pool.submit(complete,observer,lease)
                waiting=False
                while time.monotonic()-started<.65:
                    blocker.execute('SELECT pg_stat_clear_snapshot()')
                    waiting=bool(blocker.execute("SELECT 1 FROM pg_stat_activity WHERE application_name='payload-lock-test' AND wait_event_type='Lock' AND query LIKE 'SELECT * FROM agent_step%'").fetchone())
                    if waiting:break
                    time.sleep(.02)
                assert waiting and not future.done()
                time.sleep(max(0,1.05-(time.monotonic()-started)))
            with pytest.raises(LostLease):future.result(timeout=3)
    assert store.steps(lease.tenant,lease.run_id)[0]['state']=='RESERVED'
    with admin() as db:assert db.execute('SELECT count(*) AS n FROM agent_payload').fetchone()['n']==0


def test_payload_insert_denial_leaves_step_reserved_and_can_recover(store):
    _,lease=setup(store)
    with admin() as db:db.execute('REVOKE INSERT ON agent_payload FROM supportflow_ai,supportflow_agent_runtime')
    try:
        with pytest.raises(psycopg.errors.InsufficientPrivilege):complete(store,lease)
    finally:
        with admin() as db:db.execute('GRANT INSERT ON agent_payload TO supportflow_ai,supportflow_agent_runtime')
    assert store.steps(lease.tenant,lease.run_id)[0]['state']=='RESERVED'
    with admin() as db:assert db.execute('SELECT count(*) AS n FROM agent_payload').fetchone()['n']==0
    assert load(store,lease,complete(store,lease)).value==VALUE


def test_step_failure_after_payload_insert_rolls_back_both_rows(store):
    _,lease=setup(store)
    with admin() as db:
        db.execute('''CREATE FUNCTION payload_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
            IF NOT EXISTS(SELECT 1 FROM agent_payload WHERE tenant_code=NEW.tenant_code AND run_id=NEW.run_id AND step_no=NEW.step_no)
            THEN RAISE EXCEPTION 'expected payload was not inserted'; END IF;
            RAISE EXCEPTION 'synthetic payload update fault'; END $$''')
        db.execute('CREATE TRIGGER payload_test_failure BEFORE UPDATE ON agent_step FOR EACH ROW EXECUTE FUNCTION payload_test_failure()')
    try:
        with pytest.raises(psycopg.errors.RaiseException,match='synthetic payload update fault'):complete(store,lease)
    finally:
        with admin() as db:
            db.execute('DROP TRIGGER payload_test_failure ON agent_step');db.execute('DROP FUNCTION payload_test_failure()')
    assert store.steps(lease.tenant,lease.run_id)[0]['state']=='RESERVED'
    with admin() as db:assert db.execute('SELECT count(*) AS n FROM agent_payload').fetchone()['n']==0
    assert load(store,lease,complete(store,lease)).value==VALUE


def test_lost_database_commit_ack_can_be_proved_by_reloading_same_payload_and_step(store):
    _,lease=setup(store)
    @contextmanager
    def lose_ack():
        with store.connector() as connection:yield connection
        raise psycopg.OperationalError('synthetic commit acknowledgement lost')
    uncertain=RuntimeStore(lose_ack)
    with pytest.raises(psycopg.OperationalError):complete(uncertain,lease)
    step=store.steps(lease.tenant,lease.run_id)[0];assert step['state']=='SUCCEEDED'
    assert complete(store,lease)==step['result_ref'] and load(store,lease,step['result_ref']).value==VALUE
    assert store.get(lease.tenant,lease.run_id)['model_calls']==1


def test_expiry_is_not_refreshed_and_corrupt_data_is_never_replayed(store):
    _,lease=setup(store);result=complete(store,lease);value=load(store,lease,result)
    with admin() as db:
        original=db.execute('SELECT payload_json FROM agent_payload WHERE payload_id=%s',(value.payload_id,)).fetchone()['payload_json']
        db.execute("UPDATE agent_payload SET payload_json='{}' WHERE payload_id=%s",(value.payload_id,))
    with pytest.raises(RuntimeConflict):load(store,lease,result)
    with admin() as db:
        db.execute("UPDATE agent_payload SET payload_json=%s,created_at=clock_timestamp()-interval '8 days',expires_at=clock_timestamp()-interval '1 day' WHERE payload_id=%s",(original,value.payload_id))
        expiry=db.execute('SELECT expires_at FROM agent_payload WHERE payload_id=%s',(value.payload_id,)).fetchone()['expires_at']
    assert load(store,lease,result) is None
    assert complete(store,lease)==result
    with admin() as db:assert db.execute('SELECT expires_at FROM agent_payload WHERE payload_id=%s',(value.payload_id,)).fetchone()['expires_at']==expiry


def test_payload_permissions_rls_and_composite_foreign_key_remain_fail_closed(store):
    _,lease=setup(store);result=complete(store,lease)
    with store.connector() as db:
        db.autocommit=True
        assert db.execute('SELECT * FROM agent_payload').fetchall()==[]
        assert not db.execute("SELECT has_table_privilege(current_user,'agent_payload','UPDATE,DELETE,TRUNCATE') AS dangerous").fetchone()['dangerous']
        for sql in ('UPDATE agent_payload SET payload_json=\'{}\'','DELETE FROM agent_payload','TRUNCATE agent_payload','ALTER TABLE agent_payload DISABLE ROW LEVEL SECURITY'):
            with pytest.raises(psycopg.errors.InsufficientPrivilege):db.execute(sql)
        db.execute("SELECT set_config('app.tenant_code','TENANT_A',false)")
        with pytest.raises(psycopg.errors.ForeignKeyViolation):
            db.execute("""INSERT INTO agent_payload(tenant_code,payload_id,run_id,step_no,fence,payload_schema,payload_json,payload_hash)
                VALUES('TENANT_A',%s,%s,99,1,'model-output-v1','{}',%s)""",(uuid4(),lease.run_id,'a'*64))
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            db.execute("""INSERT INTO agent_payload(tenant_code,payload_id,run_id,step_no,fence,payload_schema,payload_json,payload_hash)
                VALUES('TENANT_B',%s,%s,1,1,'model-output-v1','{}',%s)""",(uuid4(),lease.run_id,'a'*64))
    with admin() as db:db.execute('CREATE POLICY payload_test_permissive ON agent_payload USING(true) WITH CHECK(true)')
    try:assert load(store,lease,result).value==VALUE and store.payload('TENANT_B',lease.run_id,load(store,lease,result).payload_id) is None
    finally:
        with admin() as db:db.execute('DROP POLICY payload_test_permissive ON agent_payload')
