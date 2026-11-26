"""Model TCP/Loop -> real reply JAR/AI authority/PG, including unknown effects."""
import sys as _sys
from pathlib import Path as _Path
_SCRIPTS_DIR = _Path(__file__).resolve().parents[2]  # SUPPORTFLOW_SCRIPT_PATHS
for _sub in ("lib", "benchmarks", "verify/fixtures"):
    _p = str(_SCRIPTS_DIR / _sub)
    if _p not in _sys.path:
        _sys.path.insert(0, _p)
from contextlib import contextmanager, nullcontext
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
from threading import Event, Thread
import time
from uuid import uuid4

import httpx
import psycopg
from psycopg.rows import dict_row

from local_env import runtime_environment
from ticket_loop_fixture import planner_server

ROOT = Path(__file__).resolve().parents[3]


@contextmanager
def delivery_proxy(base, mode, on_commit):
    evidence = dict(posts=0, committed=False)
    stop = Event()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        def log_message(self, *_): pass

        def do_POST(self):
            evidence['posts'] += 1
            body = self.rfile.read(int(self.headers['Content-Length']))
            if mode == 'absent_write':
                raw = b'{"code":503,"message":"synthetic blocked upstream","data":null}'
                self.send_response(503)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(raw)))
                self.send_header('Connection', 'close')
                self.end_headers()
                self.wfile.write(raw)
                return
            headers = {key: self.headers[key] for key in (
                'X-Tenant-Code', 'X-SupportFlow-Customer-Auth', 'X-Request-Id', 'Content-Type')}
            with httpx.Client(trust_env=False, timeout=8) as client:
                upstream = client.post(base + self.path, headers=headers, content=body)
            assert upstream.status_code == 200
            evidence['committed'] = True
            if mode == 'cancel_after_commit':
                on_commit()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(upstream.content)))
            self.send_header('Connection', 'close')
            self.end_headers()
            self.close_connection = True
            stop.wait(5)
            try:
                self.wfile.write(upstream.content)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads = True
    thread = Thread(target=lambda: server.serve_forever(poll_interval=.05), daemon=True)
    thread.start()
    try:
        yield 'http://127.0.0.1:' + str(server.server_port), evidence
    finally:
        stop.set()
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)


def verify_publication(connect, environment, passwords, secret, ai_base, runtime_env):
    from ai.runtime.customer_tools import current_message_tool, ticket_status_tool
    from ai.runtime.models import ChatProfile, RuntimeChatClient
    from ai.runtime.publication import TicketReplyHandler
    from ai.runtime.result import ResultClient
    from ai.runtime.reconciliation import ReplyReconciler
    from ai.runtime.source import SourceClient
    from ai.runtime.store import RuntimeStore
    from ai.runtime.ticket_loop import TicketReadLoop
    from ai.runtime.tickets import TicketClient
    from ai.runtime.tools import ToolExecutor, ToolRegistry
    from ai.runtime.types import RunInput
    from ai.runtime.worker import RuntimeWorker
    store = RuntimeStore(lambda: psycopg.connect(
        runtime_env['SUPPORTFLOW_RUNTIME_TEST_DSN'], row_factory=dict_row))
    with socket.socket() as reserved:
        reserved.bind(('127.0.0.1', 0))
        port = reserved.getsockname()[1]
    child = {key: value for key, value in os.environ.items() if not key.startswith('SUPPORTFLOW_')}
    child.update(SUPPORTFLOW_DB_HOST='127.0.0.1', SUPPORTFLOW_DB_PORT=environment['SUPPORTFLOW_DB_PORT'], SUPPORTFLOW_CUSTOMER_DB_PASSWORD=passwords['CUSTOMER'],
                 SUPPORTFLOW_CUSTOMER_PLATFORM_DB_PASSWORD=passwords['CUSTOMER_PLATFORM'], SUPPORTFLOW_AI_CUSTOMER_SECRET=secret, SUPPORTFLOW_JWT_SECRET=secrets.token_urlsafe(
                     48),
                 SUPPORTFLOW_RUSTFS_SECRET_KEY=secrets.token_urlsafe(32), SUPPORTFLOW_BILLING_USAGE_REPORT_INITIAL_DELAY_MS='600000')
    child = runtime_environment('customer-service', child)
    java = Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    jar, = (ROOT / 'backend/supportflow-customer-service/target').glob('*.jar')
    logs = ROOT / '.local/runtime-publication-service'
    logs.mkdir(parents=True, exist_ok=True)
    failed = False
    with (logs / 'customer-service.log').open('wb') as log:
        process = subprocess.Popen([str(java), '-Xms32m', '-Xmx384m', '-jar', str(jar), f'--server.port={port}',
                                    '--supportflow.bot.runtime-source-enabled=true', '--supportflow.bot.runtime-read-tools-enabled=true', '--supportflow.bot.runtime-result-enabled=true',
                                    *[f'--supportflow.ai.{name}-base-url={ai_base}' for name in ('bot', 'qa', 'kb', 'vision', 'speech')]],
                                   cwd=ROOT, env=child, stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        try:
            base = f'http://127.0.0.1:{port}'
            with httpx.Client(trust_env=False, timeout=1) as http:
                deadline = time.monotonic() + 60
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError('Disposable publication service exited')
                    try:
                        if http.get(base + '/actuator/health/readiness').status_code == 200:
                            break
                    except httpx.HTTPError:
                        pass
                    time.sleep(.25)
                else:
                    raise RuntimeError('Disposable publication service not ready')
            observations = []
            scenarios = {}
            recovery_observations = []
            with planner_server(scenarios, connect) as model_base:
                for index, mode in enumerate(('normal', 'lost_ack', 'absent_write', 'cancel_after_commit', 'step_ack_lost')):
                    tenant = 'PUB_' + chr(ord('A') + index)
                    session_id = 16_900_001 + index * 10
                    message_id = session_id + 1
                    customer_id = session_id + 2
                    ticket_id = session_id + 3
                    ticket_no = 'PUB_TICKET_' + tenant
                    source = RunInput(tenant, uuid4(), 'PUB_JAR_' + str(index), 1, message_id,
                                      'visitor-session:' + str(session_id), 'publication-' + str(index))
                    run = store.enqueue(source)['run_id']
                    with connect('customer_db') as db:
                        db.execute("INSERT INTO customer(id,tenant_code,customer_no,name) VALUES(%s,%s,%s,'SYNTHETIC PRIVATE CUSTOMER')", (
                            customer_id, tenant, 'C_' + tenant))
                        db.execute("INSERT INTO session(id,tenant_code,session_no,channel_id,status,last_msg_seq,customer_id,start_time) VALUES(%s,%s,%s,1,2,1,%s,CURRENT_TIMESTAMP)", (
                            session_id, tenant, source.session_no, customer_id))
                        db.execute("INSERT INTO session_message(id,tenant_code,session_id,msg_no,seq,msg_type,sender_type,visible_to,content,send_time) VALUES(%s,%s,%s,%s,1,1,1,1,%s,CURRENT_TIMESTAMP)",
                                   (message_id, tenant, session_id, 'PUB_SOURCE_' + str(index), '查询工单 ' + ticket_no))
                        db.execute("""INSERT INTO agent_event_outbox(tenant_code,event_id,run_id,message_id,session_id,session_no,event_seq,principal_ref,trace_id,runtime_version,state,attempts,fence,delivered_at)
                            VALUES(%s,%s,%s,%s,%s,%s,1,%s,%s,'runtime-v1','DELIVERED',1,1,clock_timestamp())""",
                                   (tenant, source.source_event_id, run, message_id, session_id, source.session_no, source.principal_ref, source.trace_id))
                        db.execute("INSERT INTO ticket(id,tenant_code,ticket_no,title,customer_id,session_no,status,update_time) VALUES(%s,%s,%s,'PRIVATE PUB TITLE',%s,'PUB_HISTORY',2,TIMESTAMP '2026-11-20 12:34:00')",
                                   (ticket_id, tenant, ticket_no, customer_id))
                    model_key = 'fixture-publication-' + tenant + '-model-key'
                    scenario = dict(mode='normal', ticket_no=ticket_no, customer_id=customer_id,
                                    ticket_id=ticket_id, model_http_calls=0, decisions=0)
                    scenarios['Bearer ' + model_key] = scenario
                    access = ToolExecutor(ToolRegistry([current_message_tool(
                        SourceClient(base, secret))]), frozenset({'session:read'}))
                    tools = ToolExecutor(ToolRegistry(
                        [ticket_status_tool(TicketClient(base, secret))]), frozenset({'ticket:read'}))
                    profile = ChatProfile(tenant, 'publication-planner-v1', 'fixture',
                                          'fixture-chat', model_base, model_key, prompt_version='ticket-plan-v1')
                    loop = TicketReadLoop(access, tools, RuntimeChatClient(profile))

                    class LostAck:
                        once = True
                        def __getattr__(self, name): return getattr(store, name)

                        def complete_step(self, *args):
                            value = store.complete_step(*args)
                            if self.once and args[2] == 'SUCCEEDED' and args[3].get('operation') == 'session.reply':
                                self.once = False
                                raise psycopg.OperationalError('synthetic lost publication step ack')
                            return value
                    proxy_context = delivery_proxy(base, mode, lambda: store.cancel(tenant, run)) if mode in (
                        'lost_ack', 'absent_write', 'cancel_after_commit') else nullcontext((base, None))
                    with proxy_context as (post_base, evidence):
                        handler = TicketReplyHandler(loop, ResultClient(
                            post_base, secret, deadline=2 if evidence is not None else 10), receipt_client=ResultClient(base, secret))
                        worker = RuntimeWorker(LostAck() if mode == 'step_ack_lost' else store, {
                                               'runtime-v1': handler}, 'actual-report-publisher')
                        result = worker.run_once(tenant)
                        if mode == 'step_ack_lost':
                            assert result['state'] == 'RETRY_WAIT'
                            with connect('customer_db') as db:
                                db.execute('UPDATE session SET agent_id=12345 WHERE id=%s', (session_id,))
                            result = worker.run_once(tenant)
                        if evidence is not None:
                            assert evidence['posts'] == 1 and evidence['committed'] == (mode != 'absent_write')
                    unknown = mode in ('absent_write', 'cancel_after_commit')
                    assert result['state'] == ('FAILED' if unknown else 'SUCCEEDED')
                    assert result['error_code'] == ('SIDE_EFFECT_UNKNOWN' if unknown else None)
                    expected_counts = {'normal': (2, 6, 11, 0), 'lost_ack': (2, 7, 12, 0), 'absent_write': (2, 7, 12, 0),
                                       'cancel_after_commit': (2, 6, 11, 0), 'step_ack_lost': (2, 7, 12, 1)}[mode]
                    assert (result['model_calls'], result['tool_calls'],
                            result['step_count'], result['retry_count']) == expected_counts
                    assert scenario['model_http_calls'] == 2
                    with connect('customer_db') as db:
                        messages = db.execute(
                            'SELECT sender_type,visible_to,content,seq FROM session_message WHERE tenant_code=%s AND session_id=%s ORDER BY seq', (tenant, session_id)).fetchall()
                        receipts = db.execute(
                            'SELECT count(*) FROM agent_result_receipt WHERE tenant_code=%s AND run_id=%s', (tenant, run)).fetchone()[0]
                        last_seq = db.execute('SELECT last_msg_seq FROM session WHERE id=%s',
                                              (session_id,)).fetchone()[0]
                    committed = mode != 'absent_write'
                    assert len(messages) == (2 if committed else 1) and receipts == (
                        1 if committed else 0) and last_seq == (2 if committed else 1)
                    if committed:
                        assert messages[1] == (3, 1, f'工单 {ticket_no} 当前状态：处理中。', 2)
                    writes = [step for step in store.steps(tenant, run) if step['effect'] == 'WRITE']
                    assert len(writes) == 1 and writes[0]['state'] == ('UNKNOWN' if unknown else 'SUCCEEDED')
                    if unknown:
                        following = store.enqueue(RunInput(tenant, uuid4(), source.session_no,
                                                  2, message_id + 100, source.principal_ref, 'recovery-following'))
                        assert store.claim_next(tenant, 'recovery-following') is None
                        if committed:
                            with connect('customer_db') as db:
                                db.execute('UPDATE session SET agent_id=12345 WHERE id=%s', (session_id,))
                        reconciler = ReplyReconciler(store, ResultClient(base, secret), 'operator:qualification')
                        audit = reconciler.reconcile(tenant, run)
                        after = store.get(tenant, run)
                        assert after['state'] == 'FAILED' and after['cancel_requested'] == result['cancel_requested']
                        assert after['error_code'] == (
                            'SIDE_EFFECT_CONFIRMED' if committed else 'SIDE_EFFECT_UNKNOWN')
                        for key in ('step_count', 'model_calls', 'tool_calls', 'retry_count', 'finished_at', 'deadline_at', 'attempt'):
                            assert after[key] == result[key]
                        assert (audit is not None) == committed
                        if committed:
                            assert reconciler.reconcile(tenant, run) == audit
                            assert store.claim_next(tenant, 'recovery-following')['run_id'] == following['run_id']
                            store.cancel(tenant, following['run_id'])
                        else:
                            assert store.claim_next(tenant, 'recovery-following') is None
                        with connect('customer_db') as db:
                            assert db.execute('SELECT count(*),max(seq) FROM session_message WHERE tenant_code=%s AND session_id=%s',
                                              (tenant, session_id)).fetchone() == (len(messages), last_seq)
                        assert scenario['model_http_calls'] == 2
                        recovery_observations.append(dict(case=mode, original_state=result['state'], original_error=result['error_code'],
                                                          state=after['state'], error_code=after['error_code'], audit_count=1 if audit else 0,
                                                          next_event_claimable=committed, budgets_unchanged=True, new_business_messages=0, model_http_calls=2))
                    observations.append(dict(case=mode, run_id=str(run), event_id=str(source.source_event_id), state=result['state'], error_code=result['error_code'],
                                             model_http_calls=scenario['model_http_calls'], model_reservations=result['model_calls'], tool_reservations=result[
                                                 'tool_calls'], steps=result['step_count'], retries=result['retry_count'],
                                             source_and_reply_messages=len(messages), result_receipts=receipts, last_message_seq=last_seq, write_state=writes[0]['state'],
                                             reply_committed=committed, automatic_terminal_unknown_reconciliation=False, external_channel_delivery='NOT_VERIFIED'))
            report = ROOT / '.local/verification/runtime-publication.json'
            report.parent.mkdir(parents=True, exist_ok=True)
            report.write_text(json.dumps(dict(schema_version='publication-qualification-v1', mode='SYNTHETIC_MODEL_TCP_REAL_CUSTOMER_AI_AUTHORITY_PG', cases=observations,
                                              source_enrollment='EXPLICIT_FIXTURE_SETUP', provider_usage='SYNTHETIC_VALUES', performance='NOT_MEASURED', token_cost='NOT_MEASURED'), indent=2) + '\n', encoding='utf-8')
            (report.parent / 'runtime-reconciliation.json').write_text(json.dumps(dict(schema_version='reply-reconciliation-qualification-v1',
                                                                                       mode='REAL_CUSTOMER_RECEIPT_API_RESTRICTED_PG', cases=recovery_observations, automatic_polling=False,
                                                                                       external_channel_delivery='NOT_VERIFIED', performance='NOT_MEASURED'), indent=2) + '\n', encoding='utf-8')
            print('Actual model TCP/Loop -> verified report -> signed reply API/AI authority/restricted PG: 5 publication cases passed; one reply/receipt on commit; lost ack recovered by query; absent receipt/cancel held UNKNOWN; successful Step resumed without replan/repost; external channel delivery not qualified', flush=True)
            print('Explicit terminal reply reconciliation -> actual customer receipt API/restricted PG: committed cancelled reply confirmed without restarting task or new messages; missing receipt retained UNKNOWN and session hold; budgets unchanged', flush=True)
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
                capture(logs, ROOT / '.local/verification/publication-service-errors.json', 'runtime-publication')
