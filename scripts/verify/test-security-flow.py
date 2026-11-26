"""Build JARs first, then verify actual services with disposable data and offline AI."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[1]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("", "lib", "benchmarks", "verify/fixtures"):
    _p = str((_SCRIPTS_DIR / _sub).resolve()) if _sub else str(_SCRIPTS_DIR)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from migration_engine import load_plan

ROOT = Path(__file__).resolve().parents[2]
if __name__ == '__main__':
    required_versions = {database: str(len(plan)) for database, plan in load_plan().items()}
    logs = ROOT / '.local/security-services'
    logs.mkdir(parents=True, exist_ok=True)
    stop = logs / 'stop'
    cost_guard_report = ROOT / '.local/verification/legacy-cost-guard.json'
    cost_guard_report.unlink(missing_ok=True)
    cost_guard_cases = {}
    for scenario in ([], ['--legacy'], ['--empty-init']):
        label = 'FRESH CLI INIT' if '--empty-init' in scenario else (
            'LEGACY UPGRADE' if scenario else 'FRESH DEFAULT')
        failed = False
        stop.unlink(missing_ok=True)
        with (logs / 'runtime.log').open('wb') as log:
            runtime = subprocess.Popen([sys.executable, 'scripts/security/acceptance_runtime.py', *scenario],
                                       cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            try:
                # Let the runtime remove stale markers before the health-check client starts.
                time.sleep(1)
                subprocess.run([sys.executable, 'scripts/security/test_core.py'],
                               cwd=ROOT, check=True, timeout=240)
                project = (logs / '.project-name').read_text(encoding='utf-8')
                import re
                if not re.fullmatch(r'supportflow-acceptance-[0-9a-f]{6}', project):
                    raise RuntimeError('Invalid disposable project identity')
                from security.java_readiness import verify_java_readiness
                verify_java_readiness(project)
                from security.cost_guard import verify_cost_guard
                cost_guard_cases[label] = verify_cost_guard(project)
                psql = ['docker', 'exec', project + '-postgres-1', 'psql',
                        '-U', 'supportflow', '-d', 'customer_db', '-XqAt', '-c']
                observed = subprocess.check_output(
                    [*psql, "SELECT DISTINCT usename FROM pg_stat_activity WHERE backend_type='client backend' AND pid<>pg_backend_pid()"], text=True)
                identities = set(observed.splitlines())
                assert 'supportflow' not in identities, 'Bootstrap identity leaked into application connections'
                assert {'supportflow_user', 'supportflow_customer', 'supportflow_tenant',
                        'supportflow_billing', 'supportflow_ops'} <= identities, identities
                audit = subprocess.check_output(
                    [*psql, "SELECT count(*) FILTER(WHERE phase='START'),count(*) FILTER(WHERE phase='END') FROM platform_access_audit"], text=True).strip().split('|')
                assert int(audit[0]) >= 6 and audit[0] == audit[1], audit
                billing_psql = ['docker', 'exec', project + '-postgres-1', 'psql',
                                '-U', 'supportflow', '-d', 'billing_db', '-XqAt', '-c']
                ledger = subprocess.check_output(
                    [*billing_psql, "SELECT count(*),has_table_privilege('supportflow_billing','billing_usage_batch','DELETE') FROM billing_usage_batch"], text=True).strip().split('|')
                assert int(ledger[0]) >= 1 and ledger[1] == 'f', ledger
                versions = subprocess.check_output(
                    [*psql, "SELECT count(*),bool_and(length(checksum)=64),has_table_privilege('supportflow_customer','supportflow_schema_history','INSERT') FROM supportflow_schema_history"], text=True).strip().split('|')
                assert versions == [required_versions['customer_db'], 't', 'f'], versions
                outbox_privileges = subprocess.check_output(
                    [*psql, "SELECT has_table_privilege('supportflow_customer','agent_event_outbox','SELECT'),has_table_privilege('supportflow_customer','agent_event_outbox','INSERT'),has_table_privilege('supportflow_customer','agent_event_outbox','UPDATE'),has_table_privilege('supportflow_customer','agent_event_outbox','DELETE'),has_table_privilege('supportflow_customer','agent_event_outbox','TRUNCATE')"], text=True).strip().split('|')
                assert outbox_privileges == ['t', 't', 't', 'f', 'f'], outbox_privileges
                snapshot_privileges = subprocess.check_output(
                    [*psql, "SELECT has_table_privilege('supportflow_customer','agent_source_snapshot','SELECT'),has_table_privilege('supportflow_customer','agent_source_snapshot','INSERT'),has_table_privilege('supportflow_customer','agent_source_snapshot','UPDATE'),has_table_privilege('supportflow_customer','agent_source_snapshot','DELETE'),has_table_privilege('supportflow_customer','agent_source_snapshot','TRUNCATE')"], text=True).strip().split('|')
                assert snapshot_privileges == ['t', 't', 'f', 'f', 'f'], snapshot_privileges
                result_privileges = subprocess.check_output(
                    [*psql, "SELECT has_table_privilege('supportflow_customer','agent_result_receipt','SELECT'),has_table_privilege('supportflow_customer','agent_result_receipt','INSERT'),has_table_privilege('supportflow_customer','agent_result_receipt','UPDATE'),has_table_privilege('supportflow_customer','agent_result_receipt','DELETE'),has_table_privilege('supportflow_customer','agent_result_receipt','TRUNCATE')"], text=True).strip().split('|')
                assert result_privileges == ['t', 't', 'f', 'f', 'f'], result_privileges
                ticket_privileges = subprocess.check_output(
                    [*psql, "SELECT has_table_privilege('supportflow_customer','agent_ticket_receipt','SELECT'),has_table_privilege('supportflow_customer','agent_ticket_receipt','INSERT'),has_table_privilege('supportflow_customer','agent_ticket_receipt','UPDATE'),has_table_privilege('supportflow_customer','agent_ticket_receipt','DELETE'),has_table_privilege('supportflow_customer','agent_ticket_receipt','TRUNCATE')"], text=True).strip().split('|')
                assert ticket_privileges == ['t', 't', 'f', 'f', 'f'], ticket_privileges
                billing_versions = subprocess.check_output(
                    [*billing_psql, "SELECT count(*),has_table_privilege('supportflow_billing','supportflow_schema_history','UPDATE') FROM supportflow_schema_history"], text=True).strip().split('|')
                assert billing_versions == [required_versions['billing_db'], 'f'], billing_versions
                for database in ('ai', 'user', 'tenant', 'ops'):
                    command = ['docker', 'exec', project + '-postgres-1', 'psql',
                               '-U', 'supportflow', '-d', database + '_db', '-XqAt', '-c']
                    history = subprocess.check_output([*command,
                                                       f"SELECT count(*),bool_and(length(checksum)=64),has_table_privilege('supportflow_{database}','supportflow_schema_history','SELECT'),has_table_privilege('supportflow_{database}','supportflow_schema_history','INSERT'),has_table_privilege('supportflow_{database}','supportflow_schema_history','UPDATE'),has_table_privilege('supportflow_{database}','supportflow_schema_history','DELETE') FROM supportflow_schema_history"], text=True).strip().split('|')
                    assert history == [required_versions[database + '_db'],
                                       't', 't', 'f', 'f', 'f'], (database, history)
                    if database == 'ai':
                        payload_privileges = subprocess.check_output([*command,
                                                                     "SELECT has_table_privilege('supportflow_ai','agent_payload','SELECT'),has_table_privilege('supportflow_ai','agent_payload','INSERT'),has_table_privilege('supportflow_ai','agent_payload','UPDATE'),has_table_privilege('supportflow_ai','agent_payload','DELETE'),has_table_privilege('supportflow_ai','agent_payload','TRUNCATE')"], text=True).strip().split('|')
                        assert payload_privileges == ['t', 't', 'f', 'f', 'f'], payload_privileges
                        reconciliation_privileges = subprocess.check_output([*command,
                                                                            "SELECT has_table_privilege('supportflow_ai','agent_reply_reconciliation','SELECT'),has_table_privilege('supportflow_ai','agent_reply_reconciliation','INSERT'),has_table_privilege('supportflow_ai','agent_reply_reconciliation','UPDATE'),has_table_privilege('supportflow_ai','agent_reply_reconciliation','DELETE'),has_table_privilege('supportflow_ai','agent_reply_reconciliation','TRUNCATE')"], text=True).strip().split('|')
                        assert reconciliation_privileges == ['t', 't', 'f', 'f', 'f'], reconciliation_privileges
                        ticket_reconciliation_privileges = subprocess.check_output([*command,
                                                                                   "SELECT has_table_privilege('supportflow_ai','agent_ticket_reconciliation','SELECT'),has_table_privilege('supportflow_ai','agent_ticket_reconciliation','INSERT'),has_table_privilege('supportflow_ai','agent_ticket_reconciliation','UPDATE'),has_table_privilege('supportflow_ai','agent_ticket_reconciliation','DELETE'),has_table_privilege('supportflow_ai','agent_ticket_reconciliation','TRUNCATE')"], text=True).strip().split('|')
                        assert ticket_reconciliation_privileges == [
                            't', 't', 'f', 'f', 'f'], ticket_reconciliation_privileges
                        approval_permissions = subprocess.check_output([*command,
                                                                       "SELECT has_table_privilege('supportflow_ai','agent_approval','UPDATE'),has_table_privilege('supportflow_ai','agent_approval','DELETE'),has_table_privilege('supportflow_ai','agent_approval','TRUNCATE'),has_column_privilege('supportflow_ai','agent_approval','status','UPDATE'),has_column_privilege('supportflow_ai','agent_approval','arguments_sha256','UPDATE')"], text=True).strip().split('|')
                        assert approval_permissions == ['f', 'f', 'f', 't', 'f'], approval_permissions
                print('Six database migration histories verified; baseline history receipts are read-only for runtime roles', flush=True)
                print('Observed five restricted Java database identities; mandatory platform audit receipts matched', flush=True)
                print(label + ': full security flow passed', flush=True)
            except BaseException:
                failed = True
                raise
            finally:
                stop.touch()
                try:
                    runtime.wait(timeout=90)
                except subprocess.TimeoutExpired:
                    runtime.terminate()
                    runtime.wait(timeout=15)
                if failed or runtime.returncode:
                    from security.service_diagnostics import capture
                    capture(logs, ROOT / '.local/verification/service-errors.json', label)
            if runtime.returncode:
                raise SystemExit('Security runtime failed; see .local/security-services/runtime.log')
            for command in (['docker', 'ps', '-a', '-q'], ['docker', 'volume', 'ls', '-q'], ['docker', 'network', 'ls', '-q']):
                remaining = subprocess.check_output(
                    [*command, '--filter', 'label=com.docker.compose.project=' + project], text=True).strip()
                if remaining:
                    raise RuntimeError('Disposable project resources survived cleanup: ' + project)
            print('Disposable containers, volumes and network removed', flush=True)
    cost_guard_report.parent.mkdir(parents=True, exist_ok=True)
    cost_guard_report.write_text(json.dumps(cost_guard_cases, indent=2) + '\n', encoding='utf-8')
