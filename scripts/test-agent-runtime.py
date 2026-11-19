"""Exercise durable runtime primitives on disposable PG17 using the real restricted AI role."""
import os
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
        # Both migration SQL and credential provisioning must reject a pre-existing
        # elevated capability group; all faults are in this disposable cluster.
        for unsafe, restore in [('LOGIN', 'NOLOGIN'), ('BYPASSRLS', 'NOBYPASSRLS'), ('CREATEROLE', 'NOCREATEROLE')]:
            with connect('ai_db') as connection:
                connection.execute(f'ALTER ROLE supportflow_agent_runtime {unsafe}')
                try:
                    with connection.transaction():
                        connection.execute(plans['ai_db'][-1].content)
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
                        'tests/test_runtime_store_postgres.py', 'tests/test_runtime_worker_postgres.py', f'--junitxml={report}'],
                       cwd=ROOT/'ai-service', env=test_env, check=True)


if __name__ == '__main__':
    main()
