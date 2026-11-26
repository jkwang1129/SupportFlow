"""Actual ticket reads through Worker/Executor/SDK/customer JAR and restricted PG."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[2]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
import asyncio
from dataclasses import replace
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
from uuid import uuid4

import httpx
import psycopg
from psycopg.rows import dict_row

from local_env import runtime_environment

ROOT = Path(__file__).resolve().parents[3]


def verify_ticket_tools(connect, environment, passwords, secret, ai_base, runtime_env):
    from ai.runtime.customer_tools import ticket_status_tool
    from ai.runtime.store import RuntimeStore
    from ai.runtime.tickets import TicketClient
    from ai.runtime.tools import ToolCall, ToolExecutor, ToolRegistry
    from ai.runtime.types import RunInput, Limits
    from ai.runtime.worker import RuntimeWorker, TransientFailure, PermanentFailure

    with socket.socket() as reserved:
        reserved.bind(('127.0.0.1', 0))
        port = reserved.getsockname()[1]
    child = {key: value for key, value in os.environ.items() if not key.startswith('SUPPORTFLOW_')}
    child.update(SUPPORTFLOW_DB_HOST='127.0.0.1', SUPPORTFLOW_DB_PORT=environment['SUPPORTFLOW_DB_PORT'],
                 SUPPORTFLOW_CUSTOMER_DB_PASSWORD=passwords['CUSTOMER'], SUPPORTFLOW_CUSTOMER_PLATFORM_DB_PASSWORD=passwords['CUSTOMER_PLATFORM'],
                 SUPPORTFLOW_AI_CUSTOMER_SECRET=secret, SUPPORTFLOW_JWT_SECRET=secrets.token_urlsafe(48),
                 SUPPORTFLOW_RUSTFS_SECRET_KEY=secrets.token_urlsafe(32), SUPPORTFLOW_BILLING_USAGE_REPORT_INITIAL_DELAY_MS='600000')
    child = runtime_environment('customer-service', child)
    java = Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    jar, = (ROOT / 'backend/supportflow-customer-service/target').glob('*.jar')
    logs = ROOT / '.local/runtime-ticket-service'
    logs.mkdir(parents=True, exist_ok=True)
    store = RuntimeStore(lambda: psycopg.connect(
        runtime_env['SUPPORTFLOW_RUNTIME_TEST_DSN'], row_factory=dict_row))
    failed = False
    with (logs / 'customer-service.log').open('wb') as log:
        process = subprocess.Popen([str(java), '-Xms32m', '-Xmx384m', '-jar', str(jar), f'--server.port={port}',
                                    '--supportflow.bot.runtime-read-tools-enabled=true',
                                    *[f'--supportflow.ai.{name}-base-url={ai_base}' for name in ('bot', 'qa', 'kb', 'vision', 'speech')]],
                                   cwd=ROOT, env=child, stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        try:
            base = f'http://127.0.0.1:{port}'
            with httpx.Client(trust_env=False, timeout=1) as http:
                deadline = time.monotonic() + 60
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError('Disposable ticket service exited')
                    try:
                        if http.get(base + '/actuator/health/readiness').status_code == 200:
                            break
                    except httpx.HTTPError:
                        pass
                    time.sleep(.25)
                else:
                    raise RuntimeError('Disposable ticket service not ready')
            client = TicketClient(base, secret)
            executor = ToolExecutor(ToolRegistry([ticket_status_tool(client)]), frozenset({'ticket:read'}))
            observations = []
            knowledge_source = None
            for case, mode in enumerate(('authorized_resume', 'owner_revoked', 'status_changed', 'status_changed_budget3')):
                tenant = 'TICKET_' + chr(ord('A') + case)
                session_id = 14_900_001 + case*10
                message_id = session_id + 1
                customer_id = session_id + 2
                ticket_id = session_id + 3
                ticket_no = 'TOOL_' + tenant
                source = RunInput(tenant, uuid4(), 'TICKET_JAR_' + str(case), 1, message_id,
                                  'visitor-session:' + str(session_id), 'ticket-tool-' + str(case))
                run = store.enqueue(source, Limits(max_tool_calls=3) if case == 3 else Limits())['run_id']
                if case == 0:
                    knowledge_source = (source, run)
                with connect('customer_db') as db:
                    db.execute("INSERT INTO customer(id,tenant_code,customer_no,name) VALUES(%s,%s,%s,'SYNTHETIC PRIVATE CUSTOMER')", (
                        customer_id, tenant, 'C_' + tenant))
                    db.execute("INSERT INTO session(id,tenant_code,session_no,channel_id,status,last_msg_seq,customer_id,start_time) VALUES(%s,%s,%s,1,2,1,%s,CURRENT_TIMESTAMP)",
                               (session_id, tenant, source.session_no, customer_id))
                    db.execute("INSERT INTO session_message(id,tenant_code,session_id,msg_no,seq,msg_type,sender_type,visible_to,content,send_time) VALUES(%s,%s,%s,%s,1,1,1,1,'synthetic ticket source',CURRENT_TIMESTAMP)",
                               (message_id, tenant, session_id, 'TICKET_SOURCE_' + str(case)))
                    db.execute("""INSERT INTO agent_event_outbox(tenant_code,event_id,run_id,message_id,session_id,session_no,event_seq,principal_ref,trace_id,runtime_version,state,attempts,fence,delivered_at)
                        VALUES(%s,%s,%s,%s,%s,%s,1,%s,%s,'runtime-v1','DELIVERED',1,1,clock_timestamp())""",
                               (tenant, source.source_event_id, run, message_id, session_id, source.session_no, source.principal_ref, source.trace_id))
                    db.execute("INSERT INTO ticket(id,tenant_code,ticket_no,title,\"desc\",customer_id,session_no,status,update_time) VALUES(%s,%s,%s,'PRIVATE TITLE','PRIVATE DESC',%s,'HISTORICAL',2,TIMESTAMP '2026-11-20 12:34:00')",
                               (ticket_id, tenant, ticket_no, customer_id))
                returned = []

                def handler(ctx):
                    value = executor.execute(ctx, 'ticket-1', ToolCall('get_ticket_status',
                                             'ticket-status-v2', {'ticket_no': ticket_no}))
                    assert value['status'] == (
                        4 if case == 2 and returned else 2) and value['ticket_no'] == ticket_no and 'PRIVATE' not in str(value)
                    returned.append(value['status'])
                    if len(returned) == 1:
                        raise TransientFailure('AFTER_TICKET_READ', delay_seconds=0)
                    return {'outcome': 'ticket-verified'}
                runner = RuntimeWorker(store, {'runtime-v1': handler}, 'ticket-tool-qualification')
                first = runner.run_once(tenant)
                assert first['run_id'] == run and first['state'] == 'RETRY_WAIT' and first['tool_calls'] == 2
                if case:
                    with connect('customer_db') as db:
                        if case == 1:
                            db.execute('UPDATE ticket SET customer_id=%s WHERE id=%s',
                                       (customer_id + 1, ticket_id))
                        else:
                            db.execute('UPDATE ticket SET status=4 WHERE id=%s', (ticket_id,))
                result = runner.run_once(tenant)
                assert (result['tool_calls'], result['model_calls'], result['step_count'],
                        result['retry_count']) == ((4, 0, 5, 1) if case == 2 else (3, 0, 4, 1))
                expected = (None, 'TICKET_REVOKED', None, 'BUDGET_EXHAUSTED')[case]
                assert result['state'] == ('SUCCEEDED' if case in (
                    0, 2) else 'FAILED') and result['error_code'] == expected
                assert returned == ([2, 2] if case == 0 else [2, 4] if case == 2 else [2])
                if case == 0:
                    for foreign, key, code in [(replace(source, tenant='TICKET_FOREIGN'), secret, 'TICKET_SOURCE_REVOKED'),
                                               (source, 'wrong-ticket-key-' + '0' * 40, 'TICKET_AUTHORIZATION_DENIED')]:
                        try:
                            asyncio.run(TicketClient(base, key).read(foreign, run, ticket_no))
                        except PermanentFailure as error:
                            assert error.code == code
                        else:
                            raise AssertionError('Unauthorized real ticket lookup passed')
                with connect('ai_db') as db:
                    payloads = db.execute(
                        'SELECT count(*) FROM agent_payload WHERE tenant_code=%s AND run_id=%s', (tenant, run)).fetchone()[0]
                with connect('customer_db') as db:
                    messages = db.execute(
                        'SELECT count(*) FROM session_message WHERE tenant_code=%s AND session_id=%s', (tenant, session_id)).fetchone()[0]
                    receipts = db.execute(
                        'SELECT count(*) FROM agent_result_receipt WHERE tenant_code=%s AND run_id=%s', (tenant, run)).fetchone()[0]
                    fact = db.execute('SELECT status FROM ticket WHERE tenant_code=%s AND id=%s',
                                      (tenant, ticket_id)).fetchone()[0]
                assert (payloads, messages, receipts) == ((2, 1, 0) if case
                                                          == 2 else (1, 1, 0)) and fact == (4 if case >= 2 else 2)
                observations.append(dict(case=mode, run_id=str(run), event_id=str(source.source_event_id), state=result['state'],
                                         error_code=result['error_code'], tool_calls=result['tool_calls'], model_calls=result['model_calls'], step_count=result['step_count'],
                                         retry_count=result['retry_count'], payload_rows=payloads, session_messages=messages, result_receipts=receipts,
                                         observed_ticket_status=fact, handler_statuses=returned, cache_policy='current-version', cache_refreshed=case == 2))
            from knowledge_api_fixture import verify_knowledge_api
            verify_knowledge_api(connect, base, secret, *knowledge_source)
            report = ROOT / '.local/verification/runtime-ticket-tools.json'
            report.parent.mkdir(parents=True, exist_ok=True)
            report.write_text(json.dumps(dict(schema_version='ticket-tool-qualification-v2', mode='SYNTHETIC_LOCAL_REAL_HTTP_PG', cases=observations,
                                              performance='NOT_MEASURED', token_cost='NOT_MEASURED'), indent=2) + '\n', encoding='utf-8')
            print('Actual Worker -> ticket Executor/SDK -> customer JAR/restricted PG: 4 cases passed; unchanged cache reused; changed status refreshed with new budget reservation; revoked owner and insufficient refresh budget blocked; no business message/result write; model calls 0', flush=True)
        except BaseException:
            failed = True
            raise
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            if failed:
                from security.service_diagnostics import capture
                capture(logs, ROOT / '.local/verification/ticket-service-errors.json', 'runtime-ticket')
