"""Rehearse backup and restore on two disposable PostgreSQL 17 clusters."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
from uuid import uuid4

import psycopg
from psycopg import sql

from database_init import initialize_databases
from isolated_postgres import disposable_postgres
from migration_engine import DATABASES, migrate_databases


ROOT = Path(__file__).resolve().parents[1]
ROLES = ('USER', 'TENANT', 'CUSTOMER', 'BILLING', 'OPS', 'AI', 'KB', 'CUSTOMER_PLATFORM')


def pg(environment, *command, content=None, extra=None, check=True):
    variables = {'PGHOST': '127.0.0.1', 'PGUSER': environment['SUPPORTFLOW_MIGRATION_DB_USER'],
                 'PGPASSWORD': environment['SUPPORTFLOW_DB_PASSWORD'], **(extra or {})}
    invocation = ['docker', 'exec', '-i']
    for name in variables:
        invocation.extend(('-e', name))
    return subprocess.run([*invocation, environment['SUPPORTFLOW_TEST_PG_CONTAINER'], *command],
                          input=content, capture_output=True, check=check, timeout=90, env={**os.environ, **variables})


def provision(environment):
    passwords = {role: secrets.token_hex(24) for role in ROLES}
    config = {f'SUPPORTFLOW_{role}_DB_PASSWORD': value for role, value in passwords.items()}
    script = (ROOT/'backend/deploy/db-init/40-runtime-roles.sql').read_bytes()
    pg(environment, 'psql', '-wXq', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', content=script, extra=config)
    return passwords


def seed(connect):
    for index, tenant in enumerate(('RESTORE_A', 'RESTORE_B'), start=1):
        session_id = 7050000 + index*10
        session_no = 'RESTORE_SESSION_' + str(index)
        run_id, event_id = uuid4(), uuid4()
        principal = 'visitor-session:' + str(session_id)
        with connect('customer_db') as database:
            database.execute('''INSERT INTO session(id,tenant_code,session_no,channel_id,status,last_msg_seq,start_time)
                VALUES(%s,%s,%s,1,2,1,CURRENT_TIMESTAMP)''', (session_id, tenant, session_no))
            database.execute('''INSERT INTO session_message(id,tenant_code,session_id,msg_no,seq,msg_type,sender_type,visible_to,content,send_time)
                VALUES(%s,%s,%s,%s,1,1,1,1,'synthetic restore drill',CURRENT_TIMESTAMP)''', (session_id+1, tenant, session_id, session_no))
            database.execute('''INSERT INTO agent_event_outbox(tenant_code,event_id,run_id,message_id,session_id,session_no,event_seq,
                principal_ref,trace_id,runtime_version,state,attempts,fence,delivered_at)
                VALUES(%s,%s,%s,%s,%s,%s,1,%s,'restore-drill','runtime-v1','DELIVERED',1,1,clock_timestamp())''',
                (tenant, event_id, run_id, session_id+1, session_id, session_no, principal))
        with connect('ai_db') as database:
            database.execute('''INSERT INTO agent_run(tenant_code,run_id,source_event_id,session_no,event_seq,message_id,principal_ref,
                trace_id,runtime_version,input_hash,max_steps,max_model_calls,max_tool_calls,max_retries,deadline_at)
                VALUES(%s,%s,%s,%s,1,%s,%s,'restore-drill','runtime-v1',repeat('a',64),12,4,8,2,clock_timestamp()+interval '1 hour')''',
                (tenant, run_id, event_id, session_no, session_id+1, principal))
            # Simulate a completed model step whose transient body has already been cleaned up.
            payload_id = uuid4()
            intent = {'operation': 'model.chat', 'version': 'restore-prompt', 'model': 'fixture-model',
                      'input_ref': 'a'*64, 'arguments_sha256': 'b'*64}
            intent_hash = hashlib.sha256(json.dumps({'kind': 'MODEL', 'effect': 'NONE', 'intent': intent},
                sort_keys=True, separators=(',', ':')).encode()).hexdigest()
            database.execute('''INSERT INTO agent_step(tenant_code,run_id,step_no,step_key,kind,effect,intent_hash,intent_ref,
                state,fence,result_ref,completed_at) VALUES(%s,%s,1,'restore-model','MODEL','NONE',%s,%s::jsonb,
                'SUCCEEDED',1,%s::jsonb,clock_timestamp())''',
                (tenant, run_id, intent_hash, json.dumps(intent), json.dumps({'resource_ref': 'payload:'+str(payload_id)})))
            database.execute("""UPDATE agent_run SET state='SUCCEEDED',fence=1,attempt=1,step_count=1,model_calls=1,
                finished_at=clock_timestamp() WHERE tenant_code=%s AND run_id=%s""", (tenant, run_id))
            database.execute('''INSERT INTO agent_model_usage(tenant_code,run_id,step_no,fence,intent_hash,payload_id,payload_hash,
                input_tokens,chat_output_tokens) VALUES(%s,%s,1,1,%s,%s,repeat('c',64),%s,7)''',
                (tenant, run_id, intent_hash, payload_id, 12+index))


def projection(connect):
    with connect('customer_db') as database:
        outbox = database.execute('SELECT tenant_code,event_id,run_id,message_id,state,attempts FROM agent_event_outbox ORDER BY tenant_code').fetchall()
    with connect('ai_db') as database:
        runs = database.execute('SELECT tenant_code,source_event_id,run_id,message_id,state,model_calls FROM agent_run ORDER BY tenant_code').fetchall()
        usages = database.execute('SELECT * FROM agent_model_usage ORDER BY tenant_code,run_id,step_no').fetchall()
    assert len(outbox) == len(runs) == 2
    assert [row[:4] for row in outbox] == [row[:4] for row in runs]
    assert len(usages) == 2
    return outbox, runs, usages


def table_counts(connect, name):
    with connect(name, readonly=True) as database:
        names = database.execute("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename").fetchall()
        return {table: database.execute(sql.SQL('SELECT count(*) FROM public.{}').format(sql.Identifier(table))).fetchone()[0]
                for (table,) in names}


def assert_restricted_restored_reads(environment, passwords):
    for database, role, table in (('ai_db', 'AI', 'agent_run'), ('customer_db', 'CUSTOMER', 'agent_event_outbox')):
        with psycopg.connect(host='127.0.0.1', port=environment['SUPPORTFLOW_DB_PORT'], dbname=database,
                             user='supportflow_'+role.lower(), password=passwords[role], connect_timeout=3) as connection:
            assert connection.execute('SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user').fetchone() == (False,)
            for scope, expected in (('', []), ('RESTORE_A', [('RESTORE_A',)]), ('RESTORE_B', [('RESTORE_B',)]), ('PLATFORM', [])):
                connection.execute("SELECT set_config('app.tenant_code',%s,true)", (scope,))
                rows = connection.execute(sql.SQL('SELECT tenant_code FROM public.{}').format(sql.Identifier(table))).fetchall()
                assert rows == expected
                if role == 'AI':
                    assert connection.execute('SELECT tenant_code FROM agent_model_usage').fetchall() == expected
            assert connection.execute('SELECT has_table_privilege(current_user,%s,%s)', (table, 'TRUNCATE')).fetchone() == (False,)


def main():
    with disposable_postgres(initialize=False) as (source, source_env), \
            disposable_postgres(initialize=False) as (target, target_env):
        names = sorted(DATABASES)
        initialize_databases(source_env)
        source_passwords = provision(source_env)
        seed(source)
        expected_projection = projection(source)
        expected_counts = {name: table_counts(source, name) for name in names}
        roles = pg(source_env, 'pg_dumpall', '--roles-only', '--no-role-passwords', '-w').stdout
        assert all(value.encode() not in roles for value in source_passwords.values()) and b' PASSWORD ' not in roles
        # The target fixture already owns its own bootstrap login. Retain
        # role attributes and original grantor without replacing its password.
        bootstrap_create = b'\nCREATE ROLE supportflow;\n'
        assert roles.count(bootstrap_create) == 1
        roles = roles.replace(bootstrap_create, b'\n', 1)
        archives = {name: pg(source_env, 'pg_dump', '-Fc', '-w', '-d', name).stdout for name in names}

        bad = pg(target_env, 'pg_restore', '--list', content=b'not a PostgreSQL archive', check=False)
        assert bad.returncode != 0
        with target('postgres', readonly=True) as connection:
            assert connection.execute('SELECT datname FROM pg_database WHERE datname=ANY(%s)', (names,)).fetchall() == []
        pg(target_env, 'psql', '-wXq', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', content=roles)
        for archive in archives.values():
            pg(target_env, 'pg_restore', '--create', '--exit-on-error', '-w', '-d', 'postgres', content=archive)
        passwords = provision(target_env)
        verified = migrate_databases(target_env, verify=True)
        assert len(verified) == 6 and projection(target) == expected_projection
        assert {name: table_counts(target, name) for name in names} == expected_counts
        assert_restricted_restored_reads(target_env, passwords)
        assert projection(source) == expected_projection

        report = {
            'schema_version': 'supportflow-restore-drill-v1', 'environment': 'DISPOSABLE_POSTGRES_17_SYNTHETIC_DATA',
            'databases': {name: {'archive_bytes': len(archives[name]), 'archive_sha256': hashlib.sha256(archives[name]).hexdigest(),
                                 'tables_checked': len(expected_counts[name]), 'rows_checked': sum(expected_counts[name].values())} for name in names},
            'schema_tables_verified': sum(item['schema_verified']['tables'] for item in verified),
            'matched_outbox_run_pairs': len(expected_projection[0]),
            'matched_model_usage_receipts': len(expected_projection[2]),
            'restricted_roles_verified': 2, 'invalid_archive_rejected': True,
        }
        path = ROOT/'.local/verification/database-restore-drill.json'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
        print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
