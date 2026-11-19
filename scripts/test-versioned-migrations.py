"""Disposable PG17: adopt the existing baseline, upgrade, verify, inject rollback/checksum/lock faults."""
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import psycopg
import json
from migration_engine import load_plan,run_plan

ROOT=Path(__file__).resolve().parents[1]


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
                result=run_plan(connection,plan)
                assert result["applied"]==[migration.version for migration in plan]
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
