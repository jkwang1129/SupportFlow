"""Verify index staging retention using a disposable customer database."""
import os
from pathlib import Path
import secrets
import subprocess
import sys

from psycopg import sql
from psycopg.conninfo import make_conninfo

from isolated_postgres import disposable_postgres
from migration_engine import load_plan, run_plan


ROOT = Path(__file__).resolve().parents[1]


def main():
    with disposable_postgres() as (connect, environment):
        passwords = {role: secrets.token_hex(24) for role in ('kb_stage_maint_test', 'kb_app_test', 'customer_app_test', 'public_app_test')}
        memberships = {'kb_stage_maint_test': 'supportflow_kb_stage_maintenance', 'kb_app_test': 'supportflow_kb_tenant',
                       'customer_app_test': 'supportflow_customer_tenant'}
        with connect('customer_db') as connection:
            run_plan(connection, load_plan()['customer_db'])
            for role, password in passwords.items():
                identifier = sql.Identifier(role)
                connection.execute(sql.SQL('CREATE ROLE {} LOGIN PASSWORD {}').format(identifier, sql.Literal(password)))
                connection.execute(sql.SQL('GRANT CONNECT ON DATABASE customer_db TO {}').format(identifier))
                connection.execute(sql.SQL('GRANT USAGE ON SCHEMA public TO {}').format(identifier))
                if role in memberships:
                    connection.execute(sql.SQL('GRANT {} TO {}').format(sql.Identifier(memberships[role]), identifier))
        passwords['supportflow'] = environment['SUPPORTFLOW_DB_PASSWORD']
        address = dict(host='127.0.0.1', port=environment['SUPPORTFLOW_DB_PORT'], dbname='customer_db')
        dsns = {role: make_conninfo(**address, user=role, password=password) for role, password in passwords.items()}
        evidence = ROOT / '.local/verification'
        evidence.mkdir(parents=True, exist_ok=True)
        subprocess.run([sys.executable, '-m', 'pytest', '-q', 'tests/test_kb_stage_retention_postgres.py',
                        f'--junitxml={evidence / "kb-stage-retention.xml"}'], cwd=ROOT / 'ai-service', check=True,
                       env={**os.environ, 'SUPPORTFLOW_KB_STAGE_RETENTION_TEST_DSN': dsns['kb_stage_maint_test'],
                            'SUPPORTFLOW_KB_STAGE_ADMIN_TEST_DSN': dsns['supportflow'],
                            'SUPPORTFLOW_KB_STAGE_KB_TEST_DSN': dsns['kb_app_test'],
                            'SUPPORTFLOW_KB_STAGE_CUSTOMER_TEST_DSN': dsns['customer_app_test'],
                            'SUPPORTFLOW_KB_STAGE_PUBLIC_TEST_DSN': dsns['public_app_test'],
                            'SUPPORTFLOW_KB_STAGE_TEST_REPORT': str(evidence / 'kb-stage-retention.json')})


if __name__ == '__main__':
    main()
