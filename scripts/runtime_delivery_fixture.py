"""Cross-language admission qualification using the same disposable six-database fixture."""
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time

import httpx
from psycopg.conninfo import make_conninfo

ROOT = Path(__file__).resolve().parents[1]


def verify_delivery(connect, environment, passwords, runtime_environment):
    # No local .env, bootstrap password, or real model configuration is sent to the server.
    secret = secrets.token_urlsafe(48)
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    server_env = {key: value for key, value in os.environ.items() if not key.startswith('SUPPORTFLOW_')}
    server_env.update(
        PYTHONPATH=str(ROOT/'ai-service'), PYTHONUTF8='1',
        SUPPORTFLOW_RUNTIME_TEST_DSN=runtime_environment['SUPPORTFLOW_RUNTIME_TEST_DSN'],
        SUPPORTFLOW_DELIVERY_TEST_KB_DSN=make_conninfo(host='127.0.0.1', port=environment['SUPPORTFLOW_DB_PORT'],
                                                    dbname='customer_db', user='supportflow_kb', password=passwords['KB']),
        SUPPORTFLOW_RUNTIME_CUSTOMER_TEST_SECRET=secret,
    )
    log_path = ROOT/'.local/implementation/b1-delivery-http-server.log'
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open('wb') as log:
        process = subprocess.Popen([sys.executable, 'tests/runtime_receiver_process.py', str(port)],
                                   cwd=ROOT/'ai-service', env=server_env, stdout=log, stderr=subprocess.STDOUT)
        try:
            base = f'http://127.0.0.1:{port}'
            deadline = time.monotonic()+45
            with httpx.Client(timeout=.5, trust_env=False) as client:
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError('Disposable AI receiver exited; inspect its local fixture log')
                    try:
                        if client.get(base+'/api/ai/health/ready').status_code == 200:
                            break
                    except httpx.HTTPError:
                        pass
                    time.sleep(.25)
                else:
                    raise RuntimeError('Disposable AI receiver did not become ready')
            test_env = {key: value for key, value in os.environ.items() if not key.startswith('SUPPORTFLOW_')}
            test_env.update(
                SUPPORTFLOW_DELIVERY_TEST_PROJECT=environment['SUPPORTFLOW_TEST_PG_CONTAINER'],
                SUPPORTFLOW_DELIVERY_TEST_CUSTOMER_JDBC=f"jdbc:postgresql://127.0.0.1:{environment['SUPPORTFLOW_DB_PORT']}/customer_db",
                SUPPORTFLOW_DELIVERY_TEST_CUSTOMER_PASSWORD=passwords['CUSTOMER'],
                SUPPORTFLOW_RUNTIME_CUSTOMER_TEST_SECRET=secret,
                SUPPORTFLOW_RUNTIME_DELIVERY_URL=base+'/api/ai/v1/runtime/runs', PYTHONUTF8='1',
            )
            maven = shutil.which('mvn')
            if not maven:
                raise RuntimeError('Maven is required for Java delivery qualification')
            subprocess.run([maven, '-B', '-f', 'backend/pom.xml', '-pl', 'supportflow-customer-service', '-am', 'test',
                            '-Dtest=AgentEventDeliveryPostgresTest', '-Dsurefire.failIfNoSpecifiedTests=false'],
                           cwd=ROOT, env=test_env, check=True, timeout=180)
            with connect('customer_db') as connection:
                sources = connection.execute("SELECT tenant_code,event_id,run_id,message_id,event_seq FROM agent_event_outbox WHERE session_no LIKE 'JDEL_%' ORDER BY event_id").fetchall()
            assert len(sources) == 2 and all(row[2] is not None for row in sources)
            with connect('ai_db') as connection:
                for tenant, event_id, run_id, message_id, sequence in sources:
                    row = connection.execute("SELECT tenant_code,run_id,message_id,event_seq,state,model_calls,tool_calls FROM agent_run WHERE source_event_id=%s", (event_id,)).fetchone()
                    assert row == (tenant, run_id, message_id, sequence, 'PENDING', 0, 0), 'Cross-database admission evidence did not match'
                    assert connection.execute("SELECT count(*) FROM agent_run WHERE tenant_code=%s AND source_event_id=%s", (tenant,event_id)).fetchone()[0] == 1
            print('Real Java dispatcher -> signed AI app -> restricted PostgreSQL: 2 delivery cases passed; matched source/run receipts; no model or tool calls', flush=True)
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
