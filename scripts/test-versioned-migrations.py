"""Disposable PG17: adopt the existing baseline, upgrade, verify, inject rollback/checksum/lock faults."""
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import psycopg
import json
import hashlib
from uuid import uuid4
from migration_engine import load_plan,run_plan

ROOT=Path(__file__).resolve().parents[1]


def existing_runtime_source(connection):
    """Seed a valid 006 source before adding the 007 result FKs; never clear business tables."""
    event,run,snapshot=uuid4(),uuid4(),uuid4()
    payload=json.dumps(dict(schema_version='source-v1',trusted_source=dict(tenant_code='UPGRADE_A',
        source_event_id=str(event),run_id=str(run),session_no='RESULT_UPGRADE',message_id='774002',event_seq=1,
        principal_ref='visitor-session:774001',customer_ref=None),untrusted_input=dict(role='user',content='existing input')),
        ensure_ascii=False,sort_keys=True,separators=(',',':'))
    with connection.transaction():
        connection.execute("INSERT INTO session(id,tenant_code,session_no,channel_id,status,last_msg_seq,start_time) VALUES(774001,'UPGRADE_A','RESULT_UPGRADE',1,2,1,CURRENT_TIMESTAMP)")
        connection.execute("INSERT INTO session_message(id,tenant_code,session_id,msg_no,seq,msg_type,sender_type,visible_to,content,send_time) VALUES(774002,'UPGRADE_A',774001,'RESULT_UPGRADE_MESSAGE',1,1,1,1,'existing input',CURRENT_TIMESTAMP)")
        connection.execute("""INSERT INTO agent_event_outbox(tenant_code,event_id,run_id,message_id,session_id,session_no,event_seq,principal_ref,
            trace_id,runtime_version,state,attempts,fence,delivered_at) VALUES('UPGRADE_A',%s,%s,774002,774001,'RESULT_UPGRADE',1,
            'visitor-session:774001','upgrade-test','runtime-v1','DELIVERED',1,1,clock_timestamp())""",(event,run))
        connection.execute("""INSERT INTO agent_source_snapshot(tenant_code,event_id,run_id,snapshot_id,schema_version,payload_json,payload_hash)
            VALUES('UPGRADE_A',%s,%s,%s,'source-v1',%s,%s)""",(event,run,snapshot,payload,hashlib.sha256(payload.encode()).hexdigest()))
    def read():
        return (connection.execute("SELECT * FROM agent_source_snapshot WHERE tenant_code='UPGRADE_A' AND event_id=%s",(event,)).fetchone(),
                connection.execute("SELECT * FROM agent_event_outbox WHERE tenant_code='UPGRADE_A' AND event_id=%s",(event,)).fetchone(),
                connection.execute("SELECT * FROM session_message WHERE id=774002").fetchone())
    return read


def existing_ai_step(url,*,payload=False):
    """Preserve reserved 002 work and committed 003 bodies on later upgrades."""
    sys.path.insert(0,str(ROOT/'ai-service'))
    from psycopg.rows import dict_row
    from ai.runtime.store import RuntimeStore
    from ai.runtime.types import RunInput,Lease
    class PreQuotaStore(RuntimeStore):
        @staticmethod
        def _check_model_capacity(connection, tenant, intent):
            # This fixture represents a worker before the quota table existed.
            pass

    store=PreQuotaStore(lambda:psycopg.connect(url,row_factory=dict_row))
    value=RunInput('PAYLOAD_UP',uuid4(),'PAYLOAD_UPGRADE_BODY' if payload else 'PAYLOAD_UPGRADE',1,774002,'visitor-session:774001','payload-upgrade')
    row=store.enqueue(value);lease=Lease.from_run(store.claim_next(value.tenant,'migration-worker'))
    store.reserve_step(lease,'existing-model','MODEL','NONE',{'operation':'upgrade-test','version':'test-v1'})
    if payload:
        store.complete_payload_step(lease,'existing-model','model-output-v1',{'text':'synthetic existing private checkpoint'})
        store.reserve_step(lease,'next-model','MODEL','NONE',{'operation':'upgrade-next','version':'test-v1'})
    def read():
        with psycopg.connect(url) as connection:
            return (connection.execute('SELECT * FROM agent_run WHERE tenant_code=%s AND run_id=%s',(value.tenant,row['run_id'])).fetchone(),
                    connection.execute('SELECT * FROM agent_step WHERE tenant_code=%s AND run_id=%s ORDER BY step_no',(value.tenant,row['run_id'])).fetchall(),
                    connection.execute('SELECT * FROM agent_payload WHERE tenant_code=%s AND run_id=%s',(value.tenant,row['run_id'])).fetchall() if payload else [])
    return read


def existing_knowledge(connection):
    connection.execute("""INSERT INTO kb_document(id,tenant_code,doc_no,title,status,index_status,content)
        VALUES(774100,'UPGRADE_A','KB_UPGRADE_DRAFT','synthetic draft',1,3,'private fixture')""")
    connection.execute("""INSERT INTO kb_chunk(id,tenant_code,doc_id,chunk_no,content)
        VALUES(774101,'UPGRADE_A',774100,0,'synthetic existing chunk')""")
    def read():return (connection.execute('SELECT * FROM kb_document WHERE id=774100').fetchone(),
                      connection.execute('SELECT * FROM kb_chunk WHERE id=774101').fetchone())
    return read


def existing_ticket(connection):
    connection.execute("""INSERT INTO ticket(id,tenant_code,ticket_no,title,\"desc\",session_no,source_session_id,
        priority,status,source_channel,sla_first_minutes,sla_resolve_minutes,creator,creator_name)
        VALUES(774200,'UPGRADE_A','TICKET_UPGRADE_EXISTING','existing synthetic ticket','existing private body',
            'RESULT_UPGRADE',774001,3,2,1,10,60,'synthetic-actor','synthetic creator')""")
    def read():return connection.execute('SELECT * FROM ticket WHERE id=774200').fetchone()
    return read


def existing_reconciliation(url, *, ticket=False):
    from psycopg.rows import dict_row
    from psycopg.types.json import Jsonb
    from ai.runtime.reconciliation import ReplyRecoveryCandidate, TicketRecoveryCandidate
    from ai.runtime.result import ReplyReceipt
    from ai.runtime.store import RuntimeStore
    from ai.runtime.ticket_creation import TicketCreationReceipt
    from ai.runtime.types import Lease, RunInput, fingerprint

    store = RuntimeStore(lambda: psycopg.connect(url, row_factory=dict_row))
    label = 'TICKET' if ticket else 'REPLY'
    source = RunInput(label+'_UP', uuid4(), label+'_UPGRADE', 1, 774002, 'visitor-session:774001', label.lower()+'-upgrade')
    run = store.enqueue(source)['run_id']
    lease = Lease.from_run(store.claim_next(source.tenant, 'upgrade-worker'))
    snapshot = uuid4()
    digest = 'a' * 64
    candidate_type = TicketRecoveryCandidate if ticket else ReplyRecoveryCandidate
    intent = dict(operation=candidate_type.operation, version=candidate_type.version,
                  resource_ref=source.principal_ref, input_ref=str(snapshot), arguments_sha256=digest)
    if ticket:
        # Fixture models a reservation made by the pre-approval AI005 writer.
        # The current writer requires the AI006 table before reserving tickets.
        with psycopg.connect(url) as connection:
            connection.execute('UPDATE agent_run SET step_count=1,tool_calls=1 WHERE tenant_code=%s AND run_id=%s', (source.tenant, run))
            connection.execute("""INSERT INTO agent_step(tenant_code,run_id,step_no,step_key,kind,effect,intent_hash,intent_ref,fence)
                VALUES(%s,%s,1,'old-write','TOOL','WRITE',%s,%s,%s)""",
                (source.tenant, run, fingerprint(dict(kind='TOOL', effect='WRITE', intent=intent)), Jsonb(intent), lease.fence))
    else:
        store.reserve_step(lease, 'old-write', 'TOOL', 'WRITE', intent)
    held = store.cancel(source.tenant, run)
    candidate = candidate_type.from_rows(held, store.steps(source.tenant, run))
    if ticket:
        receipt = TicketCreationReceipt(source.tenant, run, source.source_event_id, snapshot, digest, 774004, 'TK_UPGRADE')
        store.confirm_ticket_commit(candidate, receipt, 'upgrade-operator')
        read_audit = store.ticket_reconciliation
    else:
        receipt = ReplyReceipt(run, source.source_event_id, snapshot, digest, 774003, 'M_UPGRADE', 2)
        store.confirm_reply_commit(candidate, receipt, 'upgrade-operator')
        read_audit = store.reply_reconciliation

    def read():
        return store.get(source.tenant, run), store.steps(source.tenant, run), read_audit(source.tenant, run)
    return read


def existing_approval(url):
    from psycopg.rows import dict_row
    from ai.runtime.approvals import TicketApprovals
    from ai.runtime.store import RuntimeStore
    from ai.runtime.types import RunInput
    from ai.runtime.worker import RuntimeWorker

    store = RuntimeStore(lambda: psycopg.connect(url, row_factory=dict_row))
    source = RunInput('APPROVAL_UP', uuid4(), 'APPROVAL_UPGRADE', 1, 774002, 'visitor-session:774001', 'approval-upgrade')
    run = store.enqueue(source)['run_id']
    snapshot = uuid4()
    approvals = TicketApprovals(store)
    runner = RuntimeWorker(store, {'runtime-v1': lambda context: approvals.pause(
        context, snapshot, 'synthetic upgrade request', 'exact synthetic original body ')}, 'upgrade-worker')
    assert runner.run_once(source.tenant)['state'] == 'WAITING_APPROVAL'

    def read():
        return store.get(source.tenant, run), store.steps(source.tenant, run), approvals.get(source.tenant, run)
    return read


def main():
    name="supportflow-migrations-test-"+secrets.token_hex(6)
    password=secrets.token_hex(24)
    environment={**os.environ,"POSTGRES_PASSWORD":password}
    subprocess.run(["docker","run","--rm","-d","--name",name,"-e","POSTGRES_USER=supportflow","-e","POSTGRES_PASSWORD",
                    "-p","127.0.0.1::5432","--mount",f"type=bind,src={ROOT/'backend/deploy/db-init/db-init.sql'},dst=/docker-entrypoint-initdb.d/10-init.sql,readonly",
                    "pgvector/pgvector:pg17"],env=environment,check=True,stdout=subprocess.DEVNULL)
    try:
        address=subprocess.check_output(["docker","port",name,"5432/tcp"],text=True).strip().splitlines()[0]
        def dsn(database): return f"postgresql://supportflow:{password}@{address}/{database}"
        for _ in range(90):
            try:
                with psycopg.connect(dsn("customer_db"),connect_timeout=2) as connection:
                    connection.execute("SELECT 1 FROM tenant_rls_status")
                break
            except psycopg.Error: time.sleep(1)
        else: raise RuntimeError("Migration test fixture failed to initialize")
        with psycopg.connect(dsn("customer_db"),autocommit=True) as connection:
            connection.execute("""INSERT INTO file_meta(id,tenant_code,file_no,file_name,object_key,biz_type,creator)
                VALUES(770001,'PLATFORM','MIGRATION_FIXTURE','fixture.png','migration-fixture/object',2,'99')""")
        for database,plan in load_plan().items():
            with psycopg.connect(dsn(database),autocommit=True) as connection:
                applied=[]
                if database=='customer_db':
                    assert plan[-1].version=='012'
                    applied=run_plan(connection,plan[:6])["applied"]
                    read=existing_runtime_source(connection)
                    before=read()
                    upgrade007=run_plan(connection,plan[:7])
                    assert upgrade007['applied']==['007'] and read()==before
                    applied+=upgrade007['applied']
                    assert connection.execute('SELECT count(*) FROM agent_result_receipt').fetchone()==(0,)
                    read_kb=existing_knowledge(connection);before_kb=read_kb()
                    assert connection.execute("SELECT has_column_privilege('supportflow_kb_tenant','kb_document','index_status','SELECT')").fetchone()==(False,)
                    print('006 -> 007 upgrade preserved exact existing message, delivered event and immutable snapshot; result receipt table empty',flush=True)
                    upgrade008=run_plan(connection,plan[:8]);applied+=upgrade008['applied']
                    assert upgrade008['applied']==['008'] and read()==before and read_kb()==before_kb
                    read_ticket=existing_ticket(connection);before_ticket=read_ticket()
                if database=='ai_db':
                    assert [m.version for m in plan]==['001','002','003','004','005','006','007','008','009','010','011','012','013','014','015','016']
                    applied=run_plan(connection,plan[:2])["applied"]
                    read_ai=existing_ai_step(dsn(database));before_ai=read_ai()
                    applied+=run_plan(connection,plan[:3])['applied']
                    assert read_ai()==before_ai and connection.execute('SELECT count(*) FROM agent_payload').fetchone()==(0,)
                    read_body=existing_ai_step(dsn(database),payload=True);before_body=read_body()
                    print('AI 002 -> 003 upgrade preserved exact reserved Run/Step and budgets; payload table initially empty',flush=True)
                    applied+=run_plan(connection,plan[:4])['applied']
                    assert read_ai()==before_ai and read_body()==before_body
                    assert connection.execute('SELECT count(*) FROM agent_reply_reconciliation').fetchone()==(0,)
                    print('AI 003 -> 004 upgrade preserved exact leased/reserved/committed Runs, Steps, body/hash/expiry and budgets; reconciliation table empty',flush=True)
                    read_reply=existing_reconciliation(dsn(database));before_reply=read_reply()
                    applied+=run_plan(connection,plan[:5])['applied']
                    assert read_ai()==before_ai and read_body()==before_body and read_reply()==before_reply
                    assert connection.execute('SELECT count(*) FROM agent_ticket_reconciliation').fetchone()==(0,)
                    print('AI 004 -> 005 preserved exact Runtime Run/Step, payload and confirmed reply audit; ticket audit table initially empty',flush=True)
                    read_ticket_audit=existing_reconciliation(dsn(database),ticket=True);before_ticket_audit=read_ticket_audit()
                    applied+=run_plan(connection,plan[:6])['applied']
                    assert read_ai()==before_ai and read_body()==before_body and read_reply()==before_reply and read_ticket_audit()==before_ticket_audit
                    assert connection.execute('SELECT count(*) FROM agent_approval').fetchone()==(0,)
                    print('AI 005 -> 006 preserved exact Runtime rows, payload and both reconciliation ledgers; approval table initially empty',flush=True)
                    read_approval=existing_approval(dsn(database));before_approval=read_approval()
                    applied+=run_plan(connection,plan[:7])['applied']
                    assert read_ai()==before_ai and read_body()==before_body and read_reply()==before_reply and read_ticket_audit()==before_ticket_audit
                    assert read_approval()==before_approval
                    print('AI 006 -> 007 preserved waiting approval, checkpoint reference, budgets and previous audit rows',flush=True)
                    applied+=run_plan(connection,plan[:8])['applied']
                    assert read_ai()==before_ai and read_body()==before_body and read_reply()==before_reply and read_ticket_audit()==before_ticket_audit
                    assert read_approval()==before_approval
                    assert connection.execute("SELECT indisvalid FROM pg_index WHERE indexrelid='public.agent_run_trace_lookup_idx'::regclass").fetchone()==(True,)
                    print('AI 007 -> 008 added the valid trace index without changing existing Runs, Steps, payloads or decisions',flush=True)
                    applied+=run_plan(connection,plan[:9])['applied']
                    assert read_ai()==before_ai and read_body()==before_body and read_reply()==before_reply and read_ticket_audit()==before_ticket_audit
                    assert read_approval()==before_approval
                    assert connection.execute('SELECT count(*) FROM agent_model_usage').fetchone()==(0,)
                    print('AI 008 -> 009 preserved existing runtime data and created an empty usage receipt table',flush=True)
                    upgrade010=run_plan(connection,plan[:10]);applied+=upgrade010['applied']
                    assert upgrade010['applied']==['010'] and read_ai()==before_ai
                    assert connection.execute('SELECT count(*) FROM agent_model_quota').fetchone()==(0,)
                    assert connection.execute("SELECT indisvalid FROM pg_index WHERE indexrelid='public.agent_step_active_model_quota_idx'::regclass").fetchone()==(True,)
                    connection.execute("INSERT INTO agent_model_quota(tenant_code,max_active_steps) VALUES('PAYLOAD_UP',4)")
                    print('AI 009 -> 010 preserved existing runtime data and added the quota table and active-step index',flush=True)
                    upgrade011=run_plan(connection,plan[:11]);applied+=upgrade011['applied']
                    assert upgrade011['applied']==['011'] and read_ai()==before_ai
                    assert connection.execute('SELECT * FROM agent_model_quota').fetchone()==('PAYLOAD_UP',4,None,None)
                    assert connection.execute('SELECT count(*) FROM agent_model_token_reservation').fetchone()==(0,)
                    assert connection.execute("SELECT indisvalid FROM pg_index WHERE indexrelid='public.agent_model_token_reservation_day_idx'::regclass").fetchone()==(True,)
                    print('AI 010 -> 011 kept prior quotas and runtime records; daily Token policy remains disabled',flush=True)
                    connection.execute('''INSERT INTO agent_model_token_reservation(tenant_code,run_id,step_no,budget_day,reserved_tokens)
                        SELECT tenant_code,run_id,step_no,DATE '2026-01-31'+step_no-1,12 FROM agent_step WHERE tenant_code='PAYLOAD_UP' ''')
                    connection.execute('''INSERT INTO agent_model_usage(tenant_code,run_id,step_no,fence,intent_hash,payload_id,payload_hash,input_tokens,chat_output_tokens)
                        SELECT tenant_code,run_id,step_no,fence,intent_hash,gen_random_uuid(),repeat('a',64),2,3
                        FROM agent_step WHERE tenant_code='PAYLOAD_UP' AND state='SUCCEEDED' ''')
                    before_reservations=connection.execute('SELECT * FROM agent_model_token_reservation ORDER BY run_id,step_no').fetchall()
                    before_usage=connection.execute('SELECT * FROM agent_model_usage ORDER BY run_id,step_no').fetchall()
                    upgrade012=run_plan(connection,plan[:12]);applied+=upgrade012['applied']
                    assert upgrade012['applied']==['012']
                    before_days=connection.execute('SELECT tenant_code,budget_day::text,charged_tokens FROM agent_model_token_day ORDER BY budget_day').fetchall()
                    assert before_days==[('PAYLOAD_UP','2026-01-31',17),('PAYLOAD_UP','2026-02-01',12)]
                    print('AI 011 -> 012 backfilled two UTC dates from settled and unknown usage',flush=True)
                    connection.execute("UPDATE agent_model_quota SET daily_token_limit=88,tokens_per_attempt=12 WHERE tenant_code='PAYLOAD_UP'")
                    upgrade013=run_plan(connection,plan[:13]);applied+=upgrade013['applied']
                    assert upgrade013['applied']==['013']
                    assert connection.execute('SELECT * FROM agent_model_quota').fetchone()==('PAYLOAD_UP',4,88,12,None)
                    print('AI 012 -> 013 retained daily quota; monthly policy defaults to NULL',flush=True)
                    connection.execute("UPDATE agent_model_quota SET monthly_token_limit=1000 WHERE tenant_code='PAYLOAD_UP'")
                first_plan={'ai_db': plan[:14], 'customer_db': plan[:9]}.get(database, plan)
                result=run_plan(connection,first_plan)
                assert applied+result["applied"]==[migration.version for migration in first_plan]
                if database=='customer_db':
                    assert result['applied']==['009'] and read()==before and read_kb()==before_kb and read_ticket()==before_ticket
                    assert connection.execute('SELECT count(*) FROM agent_ticket_receipt').fetchone()==(0,)
                    assert connection.execute("SELECT has_column_privilege('supportflow_kb_tenant','kb_document','index_status','SELECT'),has_column_privilege('supportflow_kb_tenant','kb_document','content','SELECT'),has_table_privilege('supportflow_kb_tenant','kb_document','UPDATE,INSERT,DELETE')").fetchone()==(True,False,False)
                    for unsafe,restore in [('LOGIN','NOLOGIN'),('BYPASSRLS','NOBYPASSRLS'),('CREATEROLE','NOCREATEROLE')]:
                        connection.execute(f'ALTER ROLE supportflow_kb_tenant {unsafe}')
                        try:
                            with connection.transaction():
                                try:connection.execute(plan[7].content)
                                except psycopg.errors.RaiseException as failure:
                                    assert 'non-administrative' in str(failure)
                                    raise
                                else:raise AssertionError('Unsafe knowledge role accepted')
                        except psycopg.errors.RaiseException:pass
                        finally:connection.execute(f'ALTER ROLE supportflow_kb_tenant {restore}')
                    print('007 -> 008 preserved existing business/source/draft/chunk data; granted only index_status SELECT; 3 unsafe capability states rejected',flush=True)
                    print('008 -> 009 preserved every existing ticket/source/message/knowledge field; immutable ticket receipt initially empty; old global ticket PK retained',flush=True)
                    upgrade010=run_plan(connection,plan[:10])
                    assert upgrade010['applied']==['010'] and read_kb()==before_kb and read_ticket()==before_ticket
                    assert connection.execute('SELECT count(*) FROM kb_chunk_stage').fetchone()==(0,)
                    print('009 -> 010 preserved existing knowledge and ticket data; isolated index staging starts empty',flush=True)
                    connection.execute("SET TIME ZONE 'Asia/Shanghai'")
                    connection.execute("""INSERT INTO kb_chunk_stage(id,tenant_code,doc_id,attempt_id,chunk_no,content,create_time)
                        VALUES (774102,'UPGRADE_A',774100,'894e4ae8-4496-423f-8915-57d981597400',0,
                                'existing staged body',statement_timestamp()::timestamp - interval '2 days')""")
                    snapshot_stage = """SELECT id,tenant_code,doc_id,attempt_id,chunk_no,content,char_count,token_count,
                        embedding::text,embedding_model,create_time::timestamptz FROM kb_chunk_stage"""
                    before_stage=connection.execute(snapshot_stage).fetchall()
                    connection.execute('CREATE ROLE supportflow_kb_stage_maintenance LOGIN')
                    try:
                        with connection.transaction():
                            connection.execute(plan[10].content)
                    except psycopg.errors.RaiseException as failure:
                        assert 'non-administrative' in str(failure)
                    else:
                        raise AssertionError('Unsafe knowledge staging maintenance capability accepted')
                    connection.execute('ALTER ROLE supportflow_kb_stage_maintenance NOLOGIN')
                    upgrade011=run_plan(connection,plan[:11])
                    assert upgrade011['applied']==['011'] and read_kb()==before_kb and read_ticket()==before_ticket
                    assert connection.execute(snapshot_stage).fetchall()==before_stage
                    assert connection.execute("SELECT public.supportflow_expired_kb_stage_batch('UPGRADE_A', 100, false)").fetchone()==(1,)
                    connection.execute('RESET TIME ZONE')
                    print('010 -> 011 preserved active and staged knowledge; rejected elevated maintenance capability and previewed existing expired row',flush=True)
                    connection.execute("""INSERT INTO kb_version (id,tenant_code,doc_id,version_no,content_snapshot)
                        VALUES (774103,'UPGRADE_A',774100,1,'existing historical metadata')""")
                    previous_history = connection.execute('SELECT * FROM kb_version WHERE id=774103').fetchone()
                    upgrade012=run_plan(connection,plan)
                    assert upgrade012['applied']==['012'] and read_kb()==before_kb and read_ticket()==before_ticket
                    assert connection.execute(snapshot_stage).fetchall()==before_stage
                    assert connection.execute('SELECT * FROM kb_version WHERE id=774103').fetchone()==previous_history
                    assert connection.execute('SELECT count(*) FROM kb_index_previous').fetchone()==(0,)
                    assert connection.execute('SELECT count(*) FROM kb_index_previous_chunk').fetchone()==(0,)
                    print('011 -> 012 preserved active, staged and legacy version rows; new index rollback tables start empty',flush=True)
                if database=='ai_db':
                    assert result['applied']==['014'] and read_ai()==before_ai and read_body()==before_body and read_reply()==before_reply and read_ticket_audit()==before_ticket_audit
                    assert read_approval()==before_approval
                    assert connection.execute('SELECT * FROM agent_model_quota').fetchone()==('PAYLOAD_UP',4,88,12,1000,None,None,None)
                    after_reservations=connection.execute('SELECT * FROM agent_model_token_reservation ORDER BY run_id,step_no').fetchall()
                    assert after_reservations==[row+(None,)*6 for row in before_reservations]
                    assert connection.execute('SELECT * FROM agent_model_usage ORDER BY run_id,step_no').fetchall()==before_usage
                    assert connection.execute('SELECT tenant_code,budget_day::text,charged_tokens FROM agent_model_token_day ORDER BY budget_day').fetchall()==before_days
                    assert connection.execute('SELECT count(*) FROM agent_model_cost_day').fetchone()==(0,)
                    print('AI 013 -> 014 preserved daily and monthly Token budgets and source ledgers; cost policy starts disabled',flush=True)
                    upgrade015=run_plan(connection,plan[:15])
                    assert upgrade015['applied']==['015'] and read_ai()==before_ai and read_approval()==before_approval
                    assert read_body()==before_body and read_reply()==before_reply and read_ticket_audit()==before_ticket_audit
                    assert connection.execute('SELECT count(*) FROM agent_run_event').fetchone()==(0,)
                    print('AI 014 -> 015 preserved source and audit records; event journal starts at cutover',flush=True)
                    upgrade016=run_plan(connection,plan)
                    assert upgrade016['applied']==['016'] and read_ai()==before_ai and read_approval()==before_approval
                    assert read_body()==before_body and read_reply()==before_reply and read_ticket_audit()==before_ticket_audit
                    assert connection.execute('SELECT count(*) FROM agent_run_event').fetchone()==(0,)
                    assert connection.execute("SELECT has_function_privilege('supportflow_agent_runtime',"
                                              "'public.supportflow_expired_payload_batch(text,integer,boolean)','EXECUTE')").fetchone()==(False,)
                    print('AI 015 -> 016 preserved payloads and audit records without granting Runtime execution',flush=True)
                assert run_plan(connection,plan)["applied"]==[]
                run_plan(connection,plan,verify=True)
        runtime_password=secrets.token_hex(24)
        with psycopg.connect(dsn("customer_db"),autocommit=True) as connection:
            from psycopg import sql
            connection.execute(sql.SQL("CREATE ROLE migration_test_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD {}").format(sql.Literal(runtime_password)))
            connection.execute("GRANT CONNECT ON DATABASE customer_db TO migration_test_runtime; GRANT USAGE ON SCHEMA public TO migration_test_runtime")
        runtime_dsn=f"postgresql://migration_test_runtime:{runtime_password}@{address}/customer_db"
        cli_environment={**os.environ,"SUPPORTFLOW_DB_HOST":"127.0.0.1","SUPPORTFLOW_DB_PORT":address.rsplit(':',1)[1],
                         "SUPPORTFLOW_DB_PASSWORD":password,"SUPPORTFLOW_MIGRATION_DB_USER":"supportflow"}
        for command in ("migrate","verify"):
            result=subprocess.run([sys.executable,"scripts/db-migrate.py",command],cwd=ROOT,env=cli_environment,
                                  text=True,capture_output=True,check=True)
            report=json.loads(result.stdout)
            assert {row['database'] for row in report}==set(load_plan())
            assert all(not row['applied'] for row in report)
        rejected=subprocess.run([sys.executable,"scripts/db-migrate.py","migrate","--database","customer_db"],cwd=ROOT,
                                env={**cli_environment,"SUPPORTFLOW_MIGRATION_DB_USER":"migration_test_runtime",
                                     "SUPPORTFLOW_DB_PASSWORD":runtime_password},text=True,capture_output=True)
        assert rejected.returncode==1 and 'administrator' in rejected.stderr
        assert runtime_password not in rejected.stderr and password not in rejected.stderr
        subprocess.run([sys.executable,"-m","unittest","discover","-s","scripts/tests","-p","test_migration_engine.py","-v"],cwd=ROOT,check=True,
                       env={**os.environ,"SUPPORTFLOW_MIGRATIONS_TEST_URL":dsn("customer_db"),"SUPPORTFLOW_MIGRATIONS_RUNTIME_TEST_URL":runtime_dsn})
        print("Versioned six-database plan: initial apply, no-op repeat and verification passed; legacy file preserved",flush=True)
    finally:
        subprocess.run(["docker","rm","--force","--volumes",name],check=True,stdout=subprocess.DEVNULL)


if __name__=="__main__": main()
