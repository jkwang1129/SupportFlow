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
    store=RuntimeStore(lambda:psycopg.connect(url,row_factory=dict_row))
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
                    assert plan[-1].version=='007'
                    applied=run_plan(connection,plan[:-1])["applied"]
                    read=existing_runtime_source(connection)
                    before=read()
                if database=='ai_db':
                    assert [m.version for m in plan]==['001','002','003','004']
                    applied=run_plan(connection,plan[:2])["applied"]
                    read_ai=existing_ai_step(dsn(database));before_ai=read_ai()
                    applied+=run_plan(connection,plan[:3])['applied']
                    assert read_ai()==before_ai and connection.execute('SELECT count(*) FROM agent_payload').fetchone()==(0,)
                    read_body=existing_ai_step(dsn(database),payload=True);before_body=read_body()
                    print('AI 002 -> 003 upgrade preserved exact reserved Run/Step and budgets; payload table initially empty',flush=True)
                result=run_plan(connection,plan)
                assert applied+result["applied"]==[migration.version for migration in plan]
                if database=='customer_db':
                    assert result['applied']==['007'] and read()==before
                    assert connection.execute('SELECT count(*) FROM agent_result_receipt').fetchone()==(0,)
                    print('006 -> 007 upgrade preserved exact existing message, delivered event and immutable snapshot; new result receipt table empty',flush=True)
                if database=='ai_db':
                    assert result['applied']==['004'] and read_ai()==before_ai and read_body()==before_body
                    assert connection.execute('SELECT count(*) FROM agent_reply_reconciliation').fetchone()==(0,)
                    print('AI 003 -> 004 upgrade preserved exact leased/reserved/committed Runs, Steps, body/hash/expiry and budgets; reconciliation table empty',flush=True)
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
