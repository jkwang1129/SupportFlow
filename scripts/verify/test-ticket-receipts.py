"""Qualify receipt storage with actual restricted roles; no Agent business API."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys

import psycopg
from psycopg.conninfo import make_conninfo

from isolated_postgres import disposable_postgres
from migration_engine import load_plan, run_plan

ROOT = Path(__file__).resolve().parents[2]


def main():
    with disposable_postgres() as (connect, environment):
        for database, plan in load_plan().items():
            with connect(database) as db:
                run_plan(db, plan)
        passwords = {name: secrets.token_hex(24) for name in (
            'USER', 'TENANT', 'CUSTOMER', 'BILLING', 'OPS', 'AI', 'KB', 'CUSTOMER_PLATFORM')}
        provision_env = {**environment, 'PGPASSWORD': environment['SUPPORTFLOW_DB_PASSWORD'],
                         **{f'SUPPORTFLOW_{name}_DB_PASSWORD': value for name, value in passwords.items()}}
        command = ['docker', 'exec', '-i', '-e', 'PGPASSWORD']
        for name in passwords:
            command += ['-e', f'SUPPORTFLOW_{name}_DB_PASSWORD']
        command += [environment['SUPPORTFLOW_TEST_PG_CONTAINER'], 'psql', '-h', '127.0.0.1',
                    '-U', 'supportflow', '-d', 'postgres', '-wXq', '-v', 'ON_ERROR_STOP=1']

        def provision(): subprocess.run(command, env=provision_env, input=(ROOT / 'backend/deploy/db-init/40-runtime-roles.sql').read_text(encoding='utf-8'),
                                        text=True, check=True, stdout=subprocess.DEVNULL)
        provision()

        def dsn(role, database='customer_db'):
            return make_conninfo(host='127.0.0.1', port=environment['SUPPORTFLOW_DB_PORT'], dbname=database,
                                 user='supportflow' if role == 'ADMIN' else 'supportflow_' + role.lower(),
                                 password=environment['SUPPORTFLOW_DB_PASSWORD'] if role == 'ADMIN' else passwords[role])
        test_env = {**os.environ, **{'SUPPORTFLOW_TICKET_RECEIPT_TEST_' + label: dsn(role, 'ai_db' if role == 'AI' else 'customer_db')
                                     for label, role in [('ADMIN', 'ADMIN'), ('CUSTOMER', 'CUSTOMER'), ('KB', 'KB'), ('PLATFORM', 'CUSTOMER_PLATFORM'), ('AI', 'AI')]}}
        report = ROOT / '.local/verification/ticket-receipts.xml'
        report.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([sys.executable, '-m', 'pytest', '-q', 'scripts/tests/test_ticket_receipts_postgres.py', f'--junitxml={report}'],
                       cwd=ROOT, env=test_env, check=True)
        with connect('customer_db') as db:
            before = db.execute('SELECT * FROM agent_ticket_receipt ORDER BY tenant_code,run_id').fetchall()
            assert before
        # Provisioning must preserve both metadata and narrow immutable grants.
        provision()
        with connect('customer_db') as db:
            assert db.execute(
                'SELECT * FROM agent_ticket_receipt ORDER BY tenant_code,run_id').fetchall() == before
        with psycopg.connect(dsn('CUSTOMER')) as db:
            assert db.execute("SELECT has_table_privilege(current_user,'agent_ticket_receipt','SELECT'),has_table_privilege(current_user,'agent_ticket_receipt','INSERT'),has_table_privilege(current_user,'agent_ticket_receipt','UPDATE'),has_table_privilege(current_user,'agent_ticket_receipt','DELETE'),has_table_privilege(current_user,'agent_ticket_receipt','TRUNCATE')").fetchone() == (True, True, False, False, False)
        artifact = ROOT / '.local/verification/ticket-receipts.json'
        artifact.write_text(json.dumps(dict(schema_version='ticket-receipt-storage-v1', mode='REAL_RESTRICTED_PG_STORAGE_ONLY',
                                            rows_preserved_after_reprovision=len(before), immutable_privileges_verified=True,
                                            agent_creation_api='NOT_IMPLEMENTED', business_service_atomicity='NEXT_BATCH', performance='NOT_MEASURED'), indent=2) + '\n', encoding='utf-8')
        print('Ticket receipt storage: constrained tenant/source/ticket references and immutable restricted grants; duplicate concurrency/rollback tested; reprovision preserved rows; Agent business/API not yet implemented', flush=True)


if __name__ == '__main__':
    main()
