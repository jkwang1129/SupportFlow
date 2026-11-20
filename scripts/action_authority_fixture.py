"""Qualify the actual Java authority consumer against the running AI application."""
from pathlib import Path
import shutil
import subprocess
import sys
from uuid import uuid4

import psycopg
from psycopg.rows import dict_row

ROOT = Path(__file__).resolve().parents[1]


def verify_authority(connect, test_env, runtime_environment):
    # Import persistence only, never application startup or a developer .env.
    sys.path.insert(0, str(ROOT/'ai-service'))
    from ai.runtime.store import RuntimeStore
    from ai.runtime.types import RunInput, Lease
    store = RuntimeStore(lambda: psycopg.connect(runtime_environment['SUPPORTFLOW_RUNTIME_TEST_DSN'], row_factory=dict_row))
    event, snapshot = uuid4(), uuid4()
    value = RunInput('AUTHORITY_A', event, 'AUTHORITY_SESSION', 1, 1, 'visitor-session:7', 'authority-trace')
    row = store.enqueue(value)
    lease = Lease.from_run(store.claim_next(value.tenant, 'authority-worker', lease_seconds=60))
    assert lease.fence == 1
    store.reserve_step(lease, 'reply-1', 'TOOL', 'WRITE', dict(operation='session.reply', resource_ref=value.principal_ref,
                       input_ref=str(snapshot), arguments_sha256='a'*64, version='reply-v1'))
    # No bootstrap or AI DB credential is given to this Java test. Only references
    # and the existing customer->AI credential are required by the real client.
    child_env = {key: val for key, val in test_env.items() if not key.startswith('SUPPORTFLOW_')}
    child_env.update({key: test_env[key] for key in ('SUPPORTFLOW_RUNTIME_CUSTOMER_TEST_SECRET', 'SUPPORTFLOW_RUNTIME_DELIVERY_URL')})
    child_env.update(SUPPORTFLOW_ACTION_TEST_RUN=str(row['run_id']), SUPPORTFLOW_ACTION_TEST_EVENT=str(event),
                     SUPPORTFLOW_ACTION_TEST_SNAPSHOT=str(snapshot))
    subprocess.run([shutil.which('mvn'), '-B', '-f', 'backend/pom.xml', '-pl', 'supportflow-customer-service', '-am', 'test',
                    '-Dtest=AgentActionAuthorityHttpTest', '-Dsurefire.failIfNoSpecifiedTests=false'],
                   cwd=ROOT, env=child_env, check=True, timeout=180)
    with connect('ai_db') as connection:
        actual = connection.execute("SELECT state,cancel_requested,error_code,step_count,model_calls,tool_calls,retry_count FROM agent_run WHERE tenant_code=%s AND run_id=%s", (value.tenant,row['run_id'])).fetchone()
        assert actual == ('FAILED', True, 'SIDE_EFFECT_UNKNOWN', 1, 0, 1, 0)
        assert connection.execute("SELECT state FROM agent_step WHERE tenant_code=%s AND run_id=%s", (value.tenant,row['run_id'])).fetchone() == ('UNKNOWN',)
    print('Real Java -> authenticated AI authority -> restricted PG: signed permit verified; wrong tenant/fence/hash/key and cancelled Run denied; no business write', flush=True)
