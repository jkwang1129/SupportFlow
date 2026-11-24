"""Exercise durable runtime primitives on disposable PG17 using the real restricted AI role."""
import os
import argparse
from pathlib import Path
import secrets
import subprocess
import sys

from psycopg.conninfo import make_conninfo

from isolated_postgres import disposable_postgres
from migration_engine import load_plan, run_plan
import psycopg

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--with-java-delivery', action='store_true', help='also qualify real Java-to-Python HTTP admission')
    args = parser.parse_args()
    with disposable_postgres() as (connect, environment):
        plans = load_plan()
        for database, plan in plans.items():
            with connect(database) as connection:
                run_plan(connection, plan)
        passwords = {name: secrets.token_hex(24) for name in
                     ('USER', 'TENANT', 'CUSTOMER', 'BILLING', 'OPS', 'AI', 'KB', 'CUSTOMER_PLATFORM')}
        provision_env = {**environment, 'PGPASSWORD': environment['SUPPORTFLOW_DB_PASSWORD'],
                         **{f'SUPPORTFLOW_{name}_DB_PASSWORD': value for name, value in passwords.items()}}
        command = ['docker', 'exec', '-i', '-e', 'PGPASSWORD']
        for name in passwords:
            command += ['-e', f'SUPPORTFLOW_{name}_DB_PASSWORD']
        command += [environment['SUPPORTFLOW_TEST_PG_CONTAINER'], 'psql', '-h', '127.0.0.1',
                    '-U', 'supportflow', '-d', 'postgres', '-wXq', '-v', 'ON_ERROR_STOP=1']
        provisioning = (ROOT/'backend/deploy/db-init/40-runtime-roles.sql').read_text(encoding='utf-8')
        approval_role_guard = next(migration for migration in plans['ai_db'] if migration.version == '006')
        # Both migration SQL and credential provisioning must reject a pre-existing
        # elevated capability group; all faults are in this disposable cluster.
        for unsafe, restore in [('LOGIN', 'NOLOGIN'), ('BYPASSRLS', 'NOBYPASSRLS'), ('CREATEROLE', 'NOCREATEROLE')]:
            with connect('ai_db') as connection:
                connection.execute(f'ALTER ROLE supportflow_agent_runtime {unsafe}')
                try:
                    with connection.transaction():
                        connection.execute(approval_role_guard.content)
                except psycopg.errors.RaiseException as failure:
                    assert 'non-administrative' in str(failure)
                else:
                    raise AssertionError('Unsafe capability accepted by migration')
                try:
                    rejected = subprocess.run(command, env=provision_env, input=provisioning,
                                              text=True, capture_output=True)
                    assert rejected.returncode and 'non-administrative' in rejected.stderr
                finally:
                    connection.execute(f'ALTER ROLE supportflow_agent_runtime {restore}')
        print('Capability guard: 3 unsafe role states rejected by both migration and provisioning', flush=True)
        subprocess.run(command, env=provision_env, input=provisioning,
                       text=True, check=True, stdout=subprocess.DEVNULL)
        address = dict(host='127.0.0.1', port=environment['SUPPORTFLOW_DB_PORT'], dbname='ai_db')
        test_env = {**os.environ, 'PYTHONUTF8': '1',
                    'SUPPORTFLOW_RUNTIME_TEST_DSN': make_conninfo(**address, user='supportflow_ai', password=passwords['AI']),
                    'SUPPORTFLOW_RUNTIME_ADMIN_TEST_DSN': make_conninfo(**address, user='supportflow', password=environment['SUPPORTFLOW_DB_PASSWORD'])}
        report = ROOT/'.local/verification/runtime-store.xml'
        report.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([sys.executable, '-m', 'pytest', '-q', 'tests/test_runtime_types.py',
                        'tests/test_runtime_store_postgres.py', 'tests/test_runtime_worker_postgres.py',
                        'tests/test_runtime_api_postgres.py', 'tests/test_runtime_authority_postgres.py',
                        'tests/test_runtime_ticket_authority_postgres.py',
                        'tests/test_runtime_payloads_postgres.py',
                        'tests/test_runtime_worker_payloads_postgres.py',
                        'tests/test_runtime_tools_postgres.py',
                        'tests/test_ticket_write_tool_postgres.py',
                        'tests/test_runtime_models_postgres.py',
                        'tests/test_runtime_embeddings_postgres.py',
                        'tests/test_runtime_model_usage_postgres.py',
                        'tests/test_runtime_model_quota_postgres.py',
                        'tests/test_runtime_model_token_budget_postgres.py',
                        'tests/test_runtime_model_cost_budget_postgres.py',
                        'tests/test_brain_analysis_context.py',
                        'tests/test_knowledge_tools_postgres.py',
                        'tests/test_ticket_loop_postgres.py',
                        'tests/test_publication_postgres.py',
                        'tests/test_reconciliation_postgres.py',
                        'tests/test_ticket_reconciliation_postgres.py',
                        'tests/test_ticket_reconciliation_storage_postgres.py',
                        'tests/test_approval_storage_postgres.py',
                        'tests/test_approval_lifecycle_postgres.py',
                        'tests/test_approval_consumption_postgres.py',
                        'tests/test_approval_expiry_postgres.py',
                        'tests/test_runtime_approval_review_postgres.py',
                        'tests/test_runtime_trace_postgres.py',
                        'tests/test_runtime_run_events_postgres.py',
                        f'--junitxml={report}'],
                       cwd=ROOT/'ai-service', env=test_env, check=True)
        with connect('ai_db') as connection:
            audit_rows = connection.execute('SELECT * FROM agent_ticket_reconciliation ORDER BY tenant_code,run_id').fetchall()
            approval_rows = connection.execute('SELECT * FROM agent_approval ORDER BY tenant_code,run_id').fetchall()
            run_events = connection.execute('SELECT * FROM agent_run_event ORDER BY tenant_code,run_id,seq').fetchall()
            usage_rows = connection.execute('SELECT * FROM agent_model_usage ORDER BY tenant_code,run_id,step_no').fetchall()
            quota_rows = connection.execute('SELECT * FROM agent_model_quota ORDER BY tenant_code').fetchall()
            token_rows = connection.execute('SELECT * FROM agent_model_token_reservation ORDER BY tenant_code,run_id,step_no').fetchall()
            token_days = connection.execute('SELECT * FROM agent_model_token_day ORDER BY tenant_code,budget_day').fetchall()
            cost_days = connection.execute('SELECT * FROM agent_model_cost_day ORDER BY tenant_code,budget_day,currency').fetchall()
        subprocess.run(command, env=provision_env, input=provisioning,
                       text=True, check=True, stdout=subprocess.DEVNULL)
        with connect('ai_db') as connection:
            assert connection.execute('SELECT * FROM agent_ticket_reconciliation ORDER BY tenant_code,run_id').fetchall() == audit_rows
            assert connection.execute('SELECT * FROM agent_approval ORDER BY tenant_code,run_id').fetchall() == approval_rows
            assert connection.execute('SELECT * FROM agent_run_event ORDER BY tenant_code,run_id,seq').fetchall() == run_events
            assert connection.execute("""SELECT has_table_privilege('supportflow_ai','agent_run_event','SELECT'),
                has_table_privilege('supportflow_ai','agent_run_event','INSERT,UPDATE,DELETE,TRUNCATE'),
                has_table_privilege('supportflow_customer','agent_run_event','SELECT'),
                has_table_privilege('supportflow_kb','agent_run_event','SELECT')""").fetchone() == (True, False, False, False)
            assert connection.execute('SELECT * FROM agent_model_usage ORDER BY tenant_code,run_id,step_no').fetchall() == usage_rows
            assert connection.execute('SELECT * FROM agent_model_quota ORDER BY tenant_code').fetchall() == quota_rows
            assert connection.execute('SELECT * FROM agent_model_token_reservation ORDER BY tenant_code,run_id,step_no').fetchall() == token_rows
            assert connection.execute('SELECT * FROM agent_model_token_day ORDER BY tenant_code,budget_day').fetchall() == token_days
            assert connection.execute('SELECT * FROM agent_model_cost_day ORDER BY tenant_code,budget_day,currency').fetchall() == cost_days
            for privilege, allowed in (('SELECT', True), ('INSERT', False), ('UPDATE', False), ('DELETE', False), ('TRUNCATE', False)):
                for table in ('agent_model_quota', 'agent_model_token_day', 'agent_model_cost_day'):
                    assert connection.execute("SELECT has_table_privilege('supportflow_ai',%s,%s)",
                                              (table, privilege)).fetchone() == (allowed,)
            for privilege, allowed in (('SELECT', True), ('INSERT', True), ('UPDATE', False), ('DELETE', False), ('TRUNCATE', False)):
                for table in ('agent_model_usage', 'agent_model_token_reservation'):
                    assert connection.execute("SELECT has_table_privilege('supportflow_ai',%s,%s)",
                                              (table, privilege)).fetchone() == (allowed,)
            for role in ('supportflow_customer', 'supportflow_kb'):
                assert connection.execute("SELECT has_table_privilege(%s,'agent_model_usage','SELECT')", (role,)).fetchone() == (False,)
            privileges = connection.execute("""SELECT has_table_privilege('supportflow_ai','agent_ticket_reconciliation','SELECT'),
                has_table_privilege('supportflow_ai','agent_ticket_reconciliation','INSERT'),
                has_table_privilege('supportflow_ai','agent_ticket_reconciliation','UPDATE'),
                has_table_privilege('supportflow_ai','agent_ticket_reconciliation','DELETE'),
                has_table_privilege('supportflow_ai','agent_ticket_reconciliation','TRUNCATE'),
                has_table_privilege('supportflow_customer','agent_ticket_reconciliation','SELECT'),
                has_table_privilege('supportflow_kb','agent_ticket_reconciliation','SELECT')""").fetchone()
            assert privileges == (True, True, False, False, False, False, False)
            approval_permissions = connection.execute("""SELECT has_table_privilege('supportflow_ai','agent_approval','UPDATE'),
                has_table_privilege('supportflow_ai','agent_approval','DELETE'),has_table_privilege('supportflow_ai','agent_approval','TRUNCATE'),
                has_column_privilege('supportflow_ai','agent_approval','status','UPDATE'),
                has_column_privilege('supportflow_ai','agent_approval','arguments_sha256','UPDATE')""").fetchone()
            assert approval_permissions == (False, False, False, True, False)
        print(f'Ticket audit reprovision: {len(audit_rows)} rows preserved; AI SELECT/INSERT only, customer and KB SELECT denied', flush=True)
        print(f'Approval reprovision: {len(approval_rows)} rows preserved; only decision columns are writable', flush=True)
        print(f'Model usage reprovision: {len(usage_rows)} rows preserved; AI SELECT/INSERT only, customer and KB SELECT denied', flush=True)
        print(f'Model quota reprovision: {len(quota_rows)} rows preserved; AI SELECT only', flush=True)
        print(f'Model Token reprovision: {len(token_rows)} rows preserved; AI SELECT/INSERT only', flush=True)
        if args.with_java_delivery:
            from runtime_delivery_fixture import verify_delivery
            verify_delivery(connect, environment, passwords, test_env)


if __name__ == '__main__':
    main()
