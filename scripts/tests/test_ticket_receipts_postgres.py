"""Storage constraints only; not an Agent API, business service or throughput test."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import itertools
import json
import os
from pathlib import Path
import sys
from threading import Barrier
from uuid import uuid4

import psycopg
import pytest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from migration_engine import load_plan

pytestmark=pytest.mark.skipif(not os.getenv('SUPPORTFLOW_TICKET_RECEIPT_TEST_CUSTOMER'),reason='requires disposable receipt PostgreSQL')
IDS=itertools.count(22_000_000,10)
FIELDS=('tenant_code','run_id','event_id','snapshot_id','arguments_sha256','operation','schema_version','step_key','step_no','fence','ticket_id','ticket_no')


def admin():return psycopg.connect(os.environ['SUPPORTFLOW_TICKET_RECEIPT_TEST_ADMIN'],autocommit=True)


def business(scope=None,role='CUSTOMER'):
    db=psycopg.connect(os.environ['SUPPORTFLOW_TICKET_RECEIPT_TEST_'+role],connect_timeout=2,
                       options='-c statement_timeout=5000 -c lock_timeout=2000')
    if scope is not None:db.execute("SELECT set_config('app.tenant_code',%s,true)",(scope,))
    return db


def ticket(db,row):
    db.execute("INSERT INTO ticket(id,tenant_code,ticket_no,title,session_no,source_session_id) VALUES(%s,%s,%s,'synthetic ticket',%s,%s)",
               (row['ticket_id'],row['tenant_code'],row['ticket_no'],row['session_no'],row['session_id']))


def seed(owner='RC_A',*,with_ticket=True):
    identity=next(IDS);event,run,snapshot=uuid4(),uuid4(),uuid4();session_no='RECEIPT_'+str(identity)
    row=dict(tenant_code=owner,run_id=run,event_id=event,snapshot_id=snapshot,arguments_sha256='d'*64,operation='ticket.create',
        schema_version='ticket-create-v1',step_key='s001:1',step_no=1,fence=1,ticket_id=9_223_372_036_000_000_000+identity,
        ticket_no='RC_TICKET_'+str(identity),session_id=identity,session_no=session_no)
    payload=json.dumps(dict(schema_version='source-v1',trusted_source=dict(tenant_code=owner,source_event_id=str(event),run_id=str(run),
        session_no=session_no,message_id=str(identity+1),event_seq=1,principal_ref='visitor-session:'+str(identity),customer_ref=None),
        untrusted_input=dict(role='user',content='synthetic receipt source')),sort_keys=True,separators=(',',':'))
    with admin() as db:
        db.execute("INSERT INTO session(id,tenant_code,session_no,channel_id,status,last_msg_seq,start_time) VALUES(%s,%s,%s,1,2,1,CURRENT_TIMESTAMP)",(identity,owner,session_no))
        db.execute("INSERT INTO session_message(id,tenant_code,session_id,msg_no,seq,msg_type,sender_type,visible_to,content,send_time) VALUES(%s,%s,%s,%s,1,1,1,1,'synthetic receipt source',CURRENT_TIMESTAMP)",
                   (identity+1,owner,identity,'RC_MESSAGE_'+str(identity)))
        db.execute("""INSERT INTO agent_event_outbox(tenant_code,event_id,run_id,message_id,session_id,session_no,event_seq,principal_ref,trace_id,runtime_version,state,attempts,fence,delivered_at)
            VALUES(%s,%s,%s,%s,%s,%s,1,%s,'receipt-test','runtime-v1','DELIVERED',1,1,clock_timestamp())""",
                   (owner,event,run,identity+1,identity,session_no,'visitor-session:'+str(identity)))
        db.execute("INSERT INTO agent_source_snapshot(tenant_code,event_id,run_id,snapshot_id,schema_version,payload_json,payload_hash) VALUES(%s,%s,%s,%s,'source-v1',%s,%s)",
                   (owner,event,run,snapshot,payload,hashlib.sha256(payload.encode()).hexdigest()))
        if with_ticket:ticket(db,row)
    return row


def insert(db,row,*,deduplicate=False):
    sql='INSERT INTO agent_ticket_receipt('+','.join(FIELDS)+') VALUES('+','.join(['%s']*len(FIELDS))+')'
    if deduplicate:sql+=' ON CONFLICT DO NOTHING'
    return db.execute(sql,[row[key] for key in FIELDS]).rowcount


def test_restricted_customer_records_exact_int64_references_and_database_record_time():
    row=seed()
    with business('RC_A') as db:
        assert db.execute("SELECT rolsuper,rolbypassrls,pg_get_userbyid(relowner)=current_user FROM pg_roles,pg_class WHERE rolname=current_user AND pg_class.oid='agent_ticket_receipt'::regclass").fetchone()==(False,False,False)
        assert insert(db,row)==1
        value=db.execute('SELECT '+','.join(FIELDS)+',created_at FROM agent_ticket_receipt WHERE run_id=%s',(row['run_id'],)).fetchone()
        assert value[:-1]==tuple(row[key] for key in FIELDS) and value[-1].tzinfo is not None


@pytest.mark.parametrize('scope',[None,'','PLATFORM','platform','RC_B'])
def test_no_scope_platform_or_other_tenant_cannot_read_or_insert_this_receipt(scope):
    row=seed()
    with business('RC_A') as db:insert(db,row)
    with business(scope) as db:assert db.execute('SELECT count(*) FROM agent_ticket_receipt WHERE run_id=%s',(row['run_id'],)).fetchone()==(0,)
    with business(scope) as db:
        with pytest.raises(psycopg.errors.InsufficientPrivilege):insert(db,row | dict(run_id=uuid4()))


@pytest.mark.parametrize('operation',['UPDATE agent_ticket_receipt SET fence=fence+1 WHERE run_id=%s',
    'DELETE FROM agent_ticket_receipt WHERE run_id=%s','TRUNCATE agent_ticket_receipt'])
def test_customer_cannot_mutate_or_clear_committed_receipts(operation):
    row=seed()
    with business('RC_A') as db:insert(db,row)
    with business('RC_A') as db:
        with pytest.raises(psycopg.errors.InsufficientPrivilege):db.execute(operation,(row['run_id'],) if '%s' in operation else None)


@pytest.mark.parametrize('role',['KB','PLATFORM'])
def test_knowledge_and_platform_identities_cannot_read_receipt_ledger(role):
    with business('RC_A',role) as db:
        with pytest.raises(psycopg.errors.InsufficientPrivilege):db.execute('SELECT * FROM agent_ticket_receipt')


def test_ai_identity_cannot_connect_to_customer_database():
    with pytest.raises(psycopg.OperationalError):
        with psycopg.connect(os.environ['SUPPORTFLOW_TICKET_RECEIPT_TEST_AI'],dbname='customer_db',connect_timeout=2):pass


@pytest.mark.parametrize('fault',['foreign_ticket','foreign_source','wrong_run','wrong_snapshot'])
def test_composite_foreign_keys_bind_source_and_ticket_to_same_tenant(fault):
    row=seed();foreign=seed('RC_B')
    if fault=='foreign_ticket':row.update(ticket_id=foreign['ticket_id'],ticket_no=foreign['ticket_no'])
    elif fault=='foreign_source':row.update(event_id=foreign['event_id'],run_id=foreign['run_id'],snapshot_id=foreign['snapshot_id'])
    elif fault=='wrong_run':row['run_id']=uuid4()
    else:row['snapshot_id']=uuid4()
    with business('RC_A') as db:
        with pytest.raises(psycopg.errors.ForeignKeyViolation):insert(db,row)


@pytest.mark.parametrize('field,value',[('arguments_sha256','A'*64),('arguments_sha256','d'*63),('operation','session.reply'),
    ('schema_version','ticket-create-v2'),('step_key','../write'),('step_no',0),('step_no',101),('fence',0),('ticket_id',0),('ticket_no','bad/path')])
def test_invalid_receipt_contract_is_rejected_by_database(field,value):
    row=seed()
    with business('RC_A') as db:
        with pytest.raises(psycopg.errors.CheckViolation):insert(db,row | {field:value})


def test_duplicate_run_and_ticket_do_not_replace_original_argument_digest():
    row=seed();other=seed()
    with business('RC_A') as db:insert(db,row)
    for changed in (row | dict(arguments_sha256='e'*64),other | dict(ticket_id=row['ticket_id'],ticket_no=row['ticket_no'])):
        with business('RC_A') as db:
            with pytest.raises(psycopg.errors.UniqueViolation):insert(db,changed)
    with business('RC_A') as db:
        assert db.execute('SELECT ticket_id,arguments_sha256 FROM agent_ticket_receipt WHERE run_id=%s',(row['run_id'],)).fetchone()==(row['ticket_id'],'d'*64)


def test_eight_concurrent_duplicate_metadata_inserts_have_one_record():
    row=seed();barrier=Barrier(8)
    def submit(_):
        with business('RC_A') as db:barrier.wait(timeout=10);return insert(db,row,deduplicate=True)
    with ThreadPoolExecutor(8) as pool:assert sum(pool.map(submit,range(8)))==1
    with business('RC_A') as db:assert db.execute('SELECT count(*) FROM agent_ticket_receipt WHERE run_id=%s',(row['run_id'],)).fetchone()==(1,)


def test_business_row_and_receipt_can_commit_or_roll_back_in_same_transaction():
    row=seed(with_ticket=False)
    class RollbackProbe(Exception):pass
    with pytest.raises(RollbackProbe):
        with business('RC_A') as db:ticket(db,row);insert(db,row);raise RollbackProbe()
    with admin() as db:
        assert db.execute('SELECT count(*) FROM ticket WHERE id=%s',(row['ticket_id'],)).fetchone()==(0,)
        assert db.execute('SELECT count(*) FROM agent_ticket_receipt WHERE run_id=%s',(row['run_id'],)).fetchone()==(0,)
    with business('RC_A') as db:ticket(db,row);insert(db,row)
    with business('RC_A') as db:assert db.execute('SELECT count(*) FROM agent_ticket_receipt WHERE run_id=%s',(row['run_id'],)).fetchone()==(1,)


def test_restrictive_policy_still_denies_cross_tenant_after_accidental_broad_permissive_policy():
    row=seed()
    with business('RC_A') as db:insert(db,row)
    with admin() as db:db.execute('CREATE POLICY receipt_broad_probe ON agent_ticket_receipt TO PUBLIC USING(true) WITH CHECK(true)')
    try:
        with business('RC_B') as db:assert db.execute('SELECT count(*) FROM agent_ticket_receipt WHERE run_id=%s',(row['run_id'],)).fetchone()==(0,)
        with business('RC_B') as db:
            with pytest.raises(psycopg.errors.InsufficientPrivilege):insert(db,row | dict(run_id=uuid4()))
    finally:
        with admin() as db:db.execute('DROP POLICY receipt_broad_probe ON agent_ticket_receipt')


@pytest.mark.parametrize('unsafe,restore',[('LOGIN','NOLOGIN'),('BYPASSRLS','NOBYPASSRLS'),('SUPERUSER','NOSUPERUSER'),
    ('CREATEDB','NOCREATEDB'),('CREATEROLE','NOCREATEROLE'),('REPLICATION','NOREPLICATION')])
def test_migration_rejects_elevated_customer_capability_before_schema_changes(unsafe,restore):
    migration = next(item for item in load_plan()['customer_db'] if item.version == '009')
    with admin() as db:
        db.execute('ALTER ROLE supportflow_customer_tenant '+unsafe)
        try:
            with pytest.raises(psycopg.errors.RaiseException,match='non-administrative'):db.execute(migration.content)
        finally:db.execute('ALTER ROLE supportflow_customer_tenant '+restore)


@pytest.mark.parametrize('fault',['membership','ownership'])
def test_migration_rejects_role_membership_or_ownership_before_schema_changes(fault):
    migration = next(item for item in load_plan()['customer_db'] if item.version == '009')
    with admin() as db:
        if fault=='membership':db.execute('GRANT supportflow_platform_access TO supportflow_customer_tenant')
        else:db.execute('CREATE TABLE receipt_owner_probe(id int); ALTER TABLE receipt_owner_probe OWNER TO supportflow_customer_tenant')
        try:
            with pytest.raises(psycopg.errors.RaiseException,match='non-owner'):db.execute(migration.content)
        finally:db.execute('REVOKE supportflow_platform_access FROM supportflow_customer_tenant' if fault=='membership' else 'DROP TABLE receipt_owner_probe')
