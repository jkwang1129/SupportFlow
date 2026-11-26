"""Exercise fresh provisioning and a populated legacy upgrade using disposable PostgreSQL.

Requires Docker and psycopg[binary]. Never connects to the developer's database.
"""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
import os
from pathlib import Path
import secrets
import subprocess
import time
import psycopg
import sys
import argparse

ROOT = Path(__file__).resolve().parents[2]
INIT = ROOT / 'backend/deploy/db-init'
MIGRATION = ROOT / 'backend/supportflow-customer-service/src/main/resources/schema/customer_db_runtime_security.sql'
ROLES = ('user', 'tenant', 'customer', 'billing', 'ops', 'ai', 'kb', 'customer_platform')


def scenario(legacy, with_ai_readiness=False):
    name = 'supportflow-security-test-' + secrets.token_hex(6)
    env = dict(os.environ, POSTGRES_PASSWORD=secrets.token_urlsafe(32))
    for role in ROLES:
        env[f'SUPPORTFLOW_{role.upper()}_DB_PASSWORD'] = secrets.token_urlsafe(32)
    admin = env['POSTGRES_PASSWORD']

    def run(command, **kwargs):
        return subprocess.run(command, cwd=ROOT, env=env, check=True, text=True, **kwargs)
    run(['docker', 'run', '--rm', '-d', '--name', name, '-e', 'POSTGRES_USER=supportflow',
         '-e', 'POSTGRES_PASSWORD', '-p', '127.0.0.1::5432', '--mount',
         f'type=bind,src={INIT / "db-init.sql"},dst=/docker-entrypoint-initdb.d/10-init.sql,readonly',
         'pgvector/pgvector:pg17'], stdout=subprocess.DEVNULL)
    try:
        host = run(['docker', 'port', name, '5432/tcp'], capture_output=True).stdout.strip()

        def connect(role='supportflow', db='customer_db'):
            password = admin if role == 'supportflow' else env[
                f'SUPPORTFLOW_{role.removeprefix("supportflow_").upper()}_DB_PASSWORD']
            return psycopg.connect(f'postgresql://{role}:{password}@{host}/{db}', autocommit=True, connect_timeout=2)
        for attempt in range(90):
            try:
                with connect() as conn:
                    conn.execute('SELECT 1 FROM tenant_rls_status')
                break
            except psycopg.Error:
                time.sleep(1)
        else:
            raise RuntimeError('Test database failed to initialize')
        checks = 0

        def check(ok, label):
            nonlocal checks
            assert ok, label
            checks += 1

        def denied(conn, sql, params=None):
            try:
                conn.execute(sql, params)
            except psycopg.errors.InsufficientPrivilege:
                check(True, sql)
                return
            raise AssertionError('Unexpected privilege: ' + sql)
        with connect() as conn:
            if legacy:
                conn.execute("""INSERT INTO file_meta(id,tenant_code,file_no,file_name,object_key,biz_type,creator)
                    VALUES (9001,'PLATFORM','L1','license.png','old/unchanged-object',2,'99'),
                           (9002,'TENANT_A','L2','license.pdf','old/other-owner',2,'100'),
                           (9003,'TENANT_A','CHAT','chat.png','chat/private',5,'99');
                    INSERT INTO kb_document(id,tenant_code,doc_no,title,status) VALUES
                       (9001,'TENANT_A','A','A',3),(9002,'TENANT_B','B','B',3);
                    INSERT INTO kb_chunk(id,tenant_code,doc_id,chunk_no,content) VALUES
                       (9001,'TENANT_A',9001,0,'A'),(9002,'TENANT_B',9002,0,'B');
                    CREATE POLICY legacy_allow_all ON file_meta USING(true) WITH CHECK(true);
                    CREATE POLICY legacy_allow_all ON kb_chunk USING(true) WITH CHECK(true);
                """)
            # Unknown historical uploaders block and roll back the entire migration.
            conn.execute(
                "INSERT INTO file_meta(id,tenant_code,file_no,file_name,object_key,biz_type) VALUES (9999,'PLATFORM','INVALID','x.png','x',2)")
            try:
                conn.execute(MIGRATION.read_text(encoding='utf-8'))
                raise AssertionError('Unknown uploader accepted')
            except psycopg.errors.RaiseException:
                conn.execute('ROLLBACK')
                check(True, 'unknown uploader fails safely')
            conn.execute('DELETE FROM file_meta WHERE id=9999')
        env['PGPASSWORD'] = admin
        cmd = ['docker', 'exec', '-i', '-e', 'PGPASSWORD']
        for role in ROLES:
            cmd += ['-e', f'SUPPORTFLOW_{role.upper()}_DB_PASSWORD']
        cmd += [name, 'psql', '-h', '127.0.0.1', '-U', 'supportflow',
                '-d', 'customer_db', '-wXq', '-v', 'ON_ERROR_STOP=1']
        sql = '\n'.join((INIT / filename).read_text(encoding='utf-8')
                        for filename in ('channel-tenant-lookup.sql', 'public-bot-avatar.sql'))
        sql += '\n' + MIGRATION.read_text(encoding='utf-8')
        sql += '\n' + \
            (ROOT / 'backend/deploy/migrations/sql/customer_db_008_kb_publication_read.sql').read_text(encoding='utf-8')
        sql += '\n' + (INIT / '40-runtime-roles.sql').read_text(encoding='utf-8')
        for _ in range(2):
            result = run(cmd, input=sql, capture_output=True)
        for migration in ('customer_db_010_kb_chunk_stage.sql', 'customer_db_011_kb_stage_retention.sql',
                          'customer_db_012_kb_previous_index.sql', 'customer_db_013_kb_source_authority.sql'):
            run(cmd, input=(ROOT / 'backend/deploy/migrations/sql'
                / migration).read_text(encoding='utf-8'), capture_output=True)
        with connect() as conn:
            check(conn.execute("SELECT bool_and(NOT(rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication)) FROM pg_roles WHERE rolname=ANY(%s)", ([
                  f'supportflow_{r}' for r in ROLES],)).fetchone()[0], 'all eight runtime identities restricted')
            check(conn.execute("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid WHERE n.nspname='public' AND c.relkind='r' AND a.attname='tenant_code' AND (NOT relrowsecurity OR NOT relforcerowsecurity)").fetchone()
                  [0] == 0, 'FORCE RLS on every tenant table')
            if legacy:
                check(conn.execute('SELECT tenant_code,object_key FROM file_meta WHERE id=9001').fetchone() ==
                      ('L2r', 'old/unchanged-object'), 'legacy references and storage keys preserved')
            check(conn.execute('SELECT supportflow_license_scope(9223372036854775807)').fetchone()
                  [0] == 'L1y2p0ij32e8e7', 'maximum applicant ID namespace')
        with connect('supportflow_customer') as conn:
            for scope in ('', 'PLATFORM', 'TENANT_B', 'L2r'):
                conn.execute("SELECT set_config('app.tenant_code',%s,false)", (scope,))
                rows = conn.execute('SELECT id FROM file_meta').fetchall()
                check(rows == ([(9001,)] if legacy and scope == 'L2r' else []), 'license RLS ' + scope)
            conn.execute("SELECT set_config('app.tenant_code','TENANT_A',false)")
            check(conn.execute('SELECT content FROM kb_chunk').fetchall() ==
                  ([('A',)] if legacy else []), 'business tenant isolation')
            for sql_denied in ('TRUNCATE file_meta', 'ALTER TABLE file_meta DISABLE ROW LEVEL SECURITY',
                               'SET ROLE supportflow_platform_access', 'SELECT * FROM platform_access_audit'):
                denied(conn, sql_denied)
        with connect('supportflow_kb') as conn:
            conn.execute("SELECT set_config('app.tenant_code','TENANT_B',false)")
            check(conn.execute('SELECT content FROM kb_chunk').fetchall()
                  == ([('B',)] if legacy else []), 'KB tenant isolation')
            check(conn.execute("SELECT has_column_privilege(current_user,'kb_document','index_status','SELECT')").fetchone()[
                  0], 'KB reads publication readiness only')
            check(conn.execute("SELECT has_column_privilege(current_user,'kb_document','source_authority','SELECT')").fetchone()[
                  0], 'KB reads source authority metadata')
            denied(conn, 'SELECT * FROM file_meta')
            denied(conn, 'SELECT content FROM kb_document')
            denied(conn, 'SELECT content_snapshot FROM kb_index_previous')
            denied(conn, 'SELECT content FROM kb_index_previous_chunk')
            denied(conn, 'CREATE TABLE forbidden(id int)')
        with connect('supportflow_customer_platform') as conn:
            check(len(conn.execute('SELECT id FROM file_meta').fetchall())
                  == (2 if legacy else 0), 'platform can read licenses only')
            for sql_denied in ('SELECT * FROM session', 'SELECT * FROM kb_chunk', 'SELECT session_id FROM qa_task',
                               'UPDATE file_meta SET file_name=file_name', 'DELETE FROM platform_access_audit',
                               'SET ROLE supportflow_customer_tenant'):
                denied(conn, sql_denied)
            conn.execute(
                "INSERT INTO platform_access_audit(actor_id,operation,phase,http_status) VALUES(1,'TEST','START',200)")
        with connect('supportflow_ai', 'ai_db') as conn:
            denied(conn, 'CREATE TABLE forbidden(id int)')
        for role, forbidden_db in (('supportflow_customer', 'user_db'), ('supportflow_kb', 'ai_db'), ('supportflow_ai', 'customer_db')):
            try:
                with connect(role, forbidden_db):
                    raise AssertionError('Cross-database access granted')
            except psycopg.OperationalError:
                check(True, 'cross-database denied')
        if with_ai_readiness:
            subprocess.run([sys.executable, '-m', 'pytest', '-q', 'tests/postgres/test_schema_readiness_postgres.py'],
                           cwd=ROOT / 'ai-service', check=True, env={**os.environ,
                                                                     'SUPPORTFLOW_SCHEMA_TEST_AI_URL': f"postgresql://supportflow_ai:{env['SUPPORTFLOW_AI_DB_PASSWORD']}@{host}/ai_db",
                                                                     'SUPPORTFLOW_SCHEMA_TEST_KB_URL': f"postgresql://supportflow_kb:{env['SUPPORTFLOW_KB_DB_PASSWORD']}@{host}/customer_db",
                                                                     'SUPPORTFLOW_SCHEMA_TEST_ADMIN_URL': f"postgresql://supportflow:{admin}@{host}/ai_db"})
        print(('LEGACY' if legacy else 'FRESH')
              + f': {checks} database security assertions passed; migration applied twice', flush=True)
    finally:
        run(['docker', 'rm', '--force', '--volumes', name], stdout=subprocess.DEVNULL)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--with-ai-readiness', action='store_true')
    args = parser.parse_args()
    scenario(False, args.with_ai_readiness)
    scenario(True, args.with_ai_readiness)
