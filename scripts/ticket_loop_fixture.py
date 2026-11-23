"""Real model-protocol TCP -> durable read Loop -> actual customer JAR/PG.

Provider decisions/usage and setup data are synthetic. The scoped Worker case
submits a signed visitor message and uses actual source enrollment and delivery.
"""
import asyncio
from contextlib import contextmanager
import hmac
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
from tempfile import TemporaryDirectory
from threading import Thread
import time
from uuid import uuid4

import httpx
import psycopg
from psycopg.conninfo import make_conninfo
from psycopg.rows import dict_row

from local_env import runtime_environment
from ticket_approval_fixture import approval_role_server, verify_ticket_approval

ROOT=Path(__file__).resolve().parents[1]
SHADOW_TENANT = 'LP_J'


@contextmanager
def planner_server(scenarios,connect,*,mcp_secret=None,mcp_call=None):
    class Handler(BaseHTTPRequestHandler):
        protocol_version='HTTP/1.1'
        def do_POST(self):
            authorization = self.headers.get('Authorization','')
            length=int(self.headers.get('Content-Length','0'))
            if self.path=='/mcp' and mcp_call is not None and authorization=='Bearer '+mcp_secret and 0<length<=2048:
                request = json.loads(self.rfile.read(length))
                assert self.headers['Mcp-Method'] == request['method'] == 'tools/call'
                assert self.headers['Mcp-Name'] == request['params']['name'] == 'get_ticket_status'
                assert self.headers['MCP-Protocol-Version'] == request['params']['_meta']['io.modelcontextprotocol/protocolVersion'] == '2026-07-28'
                result = mcp_call(request['params']['arguments'])
                rpc = dict(jsonrpc='2.0', id=request['id'], result=dict(resultType='complete', content=[], structuredContent=result))
                raw = (': fixture heartbeat\r\n\r\ndata: '+json.dumps(rpc)+'\r\n\r\n').encode()
                self.send_response(200);self.send_header('Content-Type','text/event-stream');self.send_header('Content-Length',str(len(raw)))
                self.send_header('Connection','close');self.end_headers()
                self.wfile.write(raw[:80]);self.wfile.flush();self.wfile.write(raw[80:])
                return
            case=scenarios.get(authorization)
            if case is None or self.path!='/v1/chat/completions' or not 0<length<=262144:
                self.send_response(403);self.send_header('Content-Length','0');self.end_headers();return
            body=json.loads(self.rfile.read(length));case['model_http_calls']+=1
            assert body['model']=='fixture-chat' and body['n']==1 and body['stream'] is False
            status=200
            if case['mode']=='model503' and case['model_http_calls']==1:
                status=503;data={'error':'synthetic provider unavailable'}
            else:
                turn=case['decisions'];case['decisions']+=1
                query={'action':'tool','call':{'name':'get_ticket_status','version':'ticket-status-v2','arguments':{'ticket_no':case['ticket_no']}}}
                action=query if turn==0 else {'action':'report','evidence':'tool-0'}
                if case['mode']=='foreign_parameter':action['call']['arguments']['tenant_code']='LP_FOREIGN'
                elif case['mode']=='phantom_reference':action={'action':'report','evidence':'tool-99'}
                elif case['mode']=='fake_write_claim':action={'action':'report','evidence':'tool-0','text':'已退款'}
                if turn==1 and case['mode'] in ('late_status','owner_revoked'):
                    with connect('customer_db') as db:
                        if case['mode']=='late_status':db.execute('UPDATE ticket SET status=4 WHERE id=%s',(case['ticket_id'],))
                        else:db.execute('UPDATE ticket SET customer_id=%s WHERE id=%s',(case['customer_id']+1,case['ticket_id']))
                content = json.dumps(action, ensure_ascii=False)
                if case['mode']=='configured_worker':
                    if turn==0:
                        content = ' '*4736 + json.dumps(action, ensure_ascii=False, indent=2) + '\n'*8
                        case['raw_assistant_bytes'] = len(content.encode())
                    else:
                        previous = body['messages'][2]['content']
                        assert json.loads(previous) == query
                        case['canonical_assistant_bytes'] = len(previous.encode())
                        assert case['canonical_assistant_bytes'] < case['raw_assistant_bytes']
                data={'choices':[{'index':0,'finish_reason':'stop','message':{'role':'assistant','content':content}}],
                      'usage':{'prompt_tokens':13,'completion_tokens':7,'total_tokens':20}}
            raw=json.dumps(data,ensure_ascii=False).encode()
            self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)))
            self.send_header('Connection','close');self.end_headers();self.wfile.write(raw)
        def log_message(self,*_):pass
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=Thread(target=lambda:server.serve_forever(poll_interval=.05),daemon=True);thread.start()
    try:yield 'http://127.0.0.1:'+str(server.server_port)+'/v1'
    finally:server.shutdown();server.server_close();thread.join(timeout=2)


def _send_shadow_message(connect, base, secret, store, session_no, text):
    from ai.runtime.types import RunInput

    path = f'/api/customer/internal/sessions/{session_no}/messages'
    payload = dict(tenantCode=SHADOW_TENANT, senderType=1, msgType=1, visibleTo=1,
                   content=text, clientMsgNo='fixture-shadow-idempotency')
    expires = str(int(time.time()) + 20)
    canonical = '\n'.join(('supportflow-customer:v1', 'REALTIME', expires, 'POST', path, '', SHADOW_TENANT))
    signature = hmac.digest(secret.encode(), canonical.encode(), 'sha256').hex()
    headers = {'X-Tenant-Code': SHADOW_TENANT, 'X-SupportFlow-Customer-Auth': f'{expires}:{signature}'}
    original_trace = f'fixture-origin-{uuid4().hex}'
    duplicate_trace = f'fixture-duplicate-{uuid4().hex}'
    with httpx.Client(trust_env=False, timeout=3, follow_redirects=False) as client:
        assert client.post(base+path, json=payload).status_code == 401
        original = client.post(base+path, json=payload, headers=headers | {'X-Request-Id': original_trace})
        assert original.status_code == 200 and original.json()['code'] == 0
        assert original.headers['X-Request-Id'] == original_trace
        message = original.json()['data']
        duplicate = client.post(base+path, json=payload, headers=headers | {'X-Request-Id': duplicate_trace})
        assert duplicate.status_code == 200 and duplicate.json()['data']['msgId'] == message['msgId']
        assert duplicate.headers['X-Request-Id'] == duplicate_trace
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        with connect('customer_db') as db:
            rows = db.execute("""SELECT message_id,run_id,state,event_id,trace_id FROM agent_event_outbox
                WHERE tenant_code=%s AND session_no=%s""", (SHADOW_TENANT, session_no)).fetchall()
        assert len(rows) == 1
        message_id, run_id, state, event_id, stored_trace = rows[0]
        assert stored_trace == original_trace
        if state == 'DELIVERED':
            row = store.get(SHADOW_TENANT, run_id)
            assert row['state'] == 'PENDING' and row['source_event_id'] == event_id
            assert row['message_id'] == message_id == int(message['msgId']) and row['event_seq'] == 1
            assert row['trace_id'] == original_trace
            return RunInput.from_run(row), run_id
        time.sleep(.05)
    raise AssertionError('Shadow message did not reach the Runtime through the scheduled dispatcher')


def _queue_timeline_cases(connect, store, timeline):
    from ai.runtime.types import RunInput

    tenant, session, trace = 'TL_QUEUE', 'TIMELINE_QUEUE', 'timeline-queue-fixture'
    session_id, message_id, event_id = 21_800_001, 21_800_002, uuid4()
    source = RunInput(tenant, event_id, session, 1, message_id, f'visitor-session:{session_id}', trace)
    with connect('customer_db') as db:
        db.execute('''INSERT INTO session(id,tenant_code,session_no,channel_id,status,last_msg_seq,start_time)
            VALUES(%s,%s,%s,1,2,1,CURRENT_TIMESTAMP)''', (session_id, tenant, session))
        db.execute('''INSERT INTO session_message(id,tenant_code,session_id,msg_no,seq,msg_type,sender_type,visible_to,content,send_time)
            VALUES(%s,%s,%s,'TIMELINE_QUEUE_MSG',1,1,1,1,'synthetic timeline message',CURRENT_TIMESTAMP)''',
            (message_id, tenant, session_id))
        db.execute('''INSERT INTO agent_event_outbox(tenant_code,event_id,message_id,session_id,session_no,event_seq,
            principal_ref,trace_id,runtime_version) VALUES(%s,%s,%s,%s,%s,1,%s,%s,'runtime-v1')''',
            (tenant,event_id,message_id,session_id,session,source.principal_ref,trace))
    queued = timeline.for_event(tenant, event_id)
    assert queued['binding_status'] == 'outbox_only' and queued['customer_delivery']['state'] == 'PENDING'
    assert timeline.for_request(tenant, trace)['timelines'] == []
    assert timeline.for_event('LP_FOREIGN', event_id) is None
    # Only AI admission is committed in this controlled fixture, without the Java acknowledgment.
    run = store.enqueue(source)
    waiting = timeline.for_event(tenant, event_id)
    assert waiting['binding_status'] == 'awaiting_receipt' and waiting['run_id'] == run['run_id']
    assert waiting['customer_delivery']['delivered_at'] is None and waiting['python_runtime']['run']['state'] == 'PENDING'
    assert timeline.for_request(tenant, trace)['timelines'] == [waiting]
    return {'outbox_before_admission': queued, 'admitted_before_receipt': waiting}


def verify_ticket_loop(connect,environment,passwords,secret,ai_base,runtime_env):
    from ai.runtime.customer_tools import current_message_tool,ticket_status_tool
    from ai.runtime.models import ChatProfile,RuntimeChatClient
    from ai.runtime.source import SourceClient
    from ai.runtime.store import RuntimeStore
    from ai.runtime.ticket_loop import TicketReadLoop
    from ai.runtime.tickets import TicketClient
    from ai.runtime.tools import ToolExecutor,ToolRegistry
    from ai.runtime.trace_export import _metadata
    from ai.runtime.trace_timeline import AI_DSN, CUSTOMER_DSN, TimelineReader, reader
    from ai.runtime.types import RunInput
    from ai.runtime.worker import RuntimeWorker

    with socket.socket() as reserved:reserved.bind(('127.0.0.1',0));port=reserved.getsockname()[1]
    child={key:value for key,value in os.environ.items() if not key.startswith('SUPPORTFLOW_')}
    child.update(SUPPORTFLOW_DB_HOST='127.0.0.1',SUPPORTFLOW_DB_PORT=environment['SUPPORTFLOW_DB_PORT'],
                 SUPPORTFLOW_CUSTOMER_DB_PASSWORD=passwords['CUSTOMER'],SUPPORTFLOW_CUSTOMER_PLATFORM_DB_PASSWORD=passwords['CUSTOMER_PLATFORM'],
                 SUPPORTFLOW_AI_CUSTOMER_SECRET=secret,SUPPORTFLOW_JWT_SECRET=secrets.token_urlsafe(48),
                 SUPPORTFLOW_CUSTOMER_REALTIME_SECRET=secrets.token_urlsafe(48),
                 SUPPORTFLOW_RUSTFS_SECRET_KEY=secrets.token_urlsafe(32),SUPPORTFLOW_BILLING_USAGE_REPORT_INITIAL_DELAY_MS='600000')
    child=runtime_environment('customer-service',child)
    java=Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java');jar,=(ROOT/'backend/supportflow-customer-service/target').glob('*.jar')
    logs=ROOT/'.local/runtime-loop-service';logs.mkdir(parents=True,exist_ok=True);failed=False
    store=RuntimeStore(lambda:psycopg.connect(runtime_env['SUPPORTFLOW_RUNTIME_TEST_DSN'],row_factory=dict_row))
    trace_ai_dsn = runtime_env['SUPPORTFLOW_RUNTIME_TEST_DSN']
    trace_customer_dsn = make_conninfo(host='127.0.0.1', port=environment['SUPPORTFLOW_DB_PORT'],
                                       dbname='customer_db', user='supportflow_customer', password=passwords['CUSTOMER'])
    timeline = TimelineReader(reader(trace_customer_dsn, ('public.agent_event_outbox',)), RuntimeStore(reader(
        trace_ai_dsn, ('public.agent_run', 'public.agent_step', 'public.agent_approval'))))
    timeline_cases = _queue_timeline_cases(connect, store, timeline)
    with approval_role_server(SHADOW_TENANT) as role_base, (logs/'customer-service.log').open('wb') as log:
        process=subprocess.Popen([str(java),'-Xms32m','-Xmx384m','-jar',str(jar),f'--server.port={port}',
            '--supportflow.bot.runtime-source-enabled=true','--supportflow.bot.runtime-read-tools-enabled=true',
            '--supportflow.bot.runtime-ticket-create-enabled=true',
            '--supportflow.bot.runtime-approval-review-enabled=true', f'--supportflow.user.internal-base-url={role_base}',
            '--supportflow.bot.enabled=false', '--supportflow.bot.runtime-dispatch-enabled=true',
            f'--supportflow.bot.runtime-dispatch-tenant={SHADOW_TENANT}', '--supportflow.bot.runtime-shadow-admission-enabled=true',
            *[f'--supportflow.ai.{name}-base-url={ai_base}' for name in ('bot','qa','kb','vision','speech')]],
            cwd=ROOT,env=child,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
        try:
            base=f'http://127.0.0.1:{port}'
            with httpx.Client(trust_env=False,timeout=1) as http:
                deadline=time.monotonic()+60
                while time.monotonic()<deadline:
                    if process.poll() is not None:raise RuntimeError('Disposable Loop customer service exited')
                    try:
                        if http.get(base+'/actuator/health/readiness').status_code==200:break
                    except httpx.HTTPError:pass
                    time.sleep(.25)
                else:raise RuntimeError('Disposable Loop customer service not ready')
            observations=[];scenarios={}
            mcp_key, mcp_runs = secrets.token_urlsafe(48), []
            direct_tickets = TicketClient(base, secret)

            def mcp_read(arguments):
                assert set(arguments) == {'tenant_code', 'source_event_id', 'run_id', 'ticket_no'}
                assert arguments['tenant_code'] == SHADOW_TENANT
                row = store.get(SHADOW_TENANT, arguments['run_id'])
                assert str(row['source_event_id']) == arguments['source_event_id']
                value = asyncio.run(direct_tickets.read(RunInput.from_run(row), row['run_id'], arguments['ticket_no']))
                mcp_runs.append(row['run_id'])
                return value.result() | {'tenant_code': SHADOW_TENANT}

            with planner_server(scenarios,connect,mcp_secret=mcp_key,mcp_call=mcp_read) as model_base:
                for index,mode in enumerate(('normal','late_status','owner_revoked','foreign_parameter','phantom_reference','fake_write_claim','model503','turn_limit','process_restart','configured_worker')):
                    tenant='LP_'+chr(ord('A')+index);session_id=15_900_001+index*10;message_id=session_id+1;customer_id=session_id+2;ticket_id=session_id+3
                    ticket_no='LOOP_'+tenant;source=RunInput(tenant,uuid4(),'LOOP_JAR_'+str(index),1,message_id,'visitor-session:'+str(session_id),'loop-'+str(index))
                    from_fixture = mode != 'configured_worker'
                    run=store.enqueue(source)['run_id'] if from_fixture else None
                    text = '请查询工单 '+ticket_no+' 的状态'
                    with connect('customer_db') as db:
                        db.execute("INSERT INTO customer(id,tenant_code,customer_no,name) VALUES(%s,%s,%s,'SYNTHETIC PRIVATE LOOP CUSTOMER')",(customer_id,tenant,'C_'+tenant))
                        db.execute("INSERT INTO session(id,tenant_code,session_no,channel_id,status,last_msg_seq,customer_id,start_time) VALUES(%s,%s,%s,1,2,%s,%s,CURRENT_TIMESTAMP)",(session_id,tenant,source.session_no,int(from_fixture),customer_id))
                        if from_fixture:
                            db.execute("INSERT INTO session_message(id,tenant_code,session_id,msg_no,seq,msg_type,sender_type,visible_to,content,send_time) VALUES(%s,%s,%s,%s,1,1,1,1,%s,CURRENT_TIMESTAMP)",
                                       (message_id,tenant,session_id,'LOOP_SOURCE_'+str(index),text))
                            db.execute("""INSERT INTO agent_event_outbox(tenant_code,event_id,run_id,message_id,session_id,session_no,event_seq,principal_ref,trace_id,runtime_version,state,attempts,fence,delivered_at)
                                VALUES(%s,%s,%s,%s,%s,%s,1,%s,%s,'runtime-v1','DELIVERED',1,1,clock_timestamp())""",
                                       (tenant,source.source_event_id,run,message_id,session_id,source.session_no,source.principal_ref,source.trace_id))
                        db.execute("INSERT INTO ticket(id,tenant_code,ticket_no,title,\"desc\",customer_id,session_no,status,update_time) VALUES(%s,%s,%s,'PRIVATE LOOP TITLE','PRIVATE LOOP DESC',%s,'LOOP_HISTORY',2,TIMESTAMP '2026-11-20 12:34:00')",
                                   (ticket_id,tenant,ticket_no,customer_id))
                    if not from_fixture:
                        assert tenant == SHADOW_TENANT
                        source, run = _send_shadow_message(connect, base, child['SUPPORTFLOW_CUSTOMER_REALTIME_SECRET'],
                                                           store, source.session_no, text)
                        admitted = timeline.for_event(tenant, source.source_event_id)
                        assert admitted['binding_status'] == 'matched' and admitted['trace_id'] == source.trace_id
                        assert admitted['python_runtime']['run']['state'] == 'PENDING' and not admitted['python_runtime']['steps']
                        timeline_cases['visitor_admitted'] = admitted
                    model_key='fixture-loop-'+tenant+'-model-credential'
                    scenario=dict(mode=mode,ticket_no=ticket_no,customer_id=customer_id,ticket_id=ticket_id,model_http_calls=0,decisions=0)
                    scenarios['Bearer '+model_key]=scenario
                    access=ToolExecutor(ToolRegistry([current_message_tool(SourceClient(base,secret))]),frozenset({'session:read'}))
                    tools=ToolExecutor(ToolRegistry([ticket_status_tool(TicketClient(base,secret))]),frozenset({'ticket:read'}))
                    profile=ChatProfile(tenant,'loop-planner-v1','fixture','fixture-chat',model_base,model_key,prompt_version='ticket-plan-v1')
                    loop=TicketReadLoop(access,tools,RuntimeChatClient(profile),max_turns=1 if mode=='turn_limit' else 4)
                    worker=RuntimeWorker(store,{'runtime-v1':loop},'actual-ticket-loop')
                    if mode=='process_restart':
                        # No customer/admin DB credential or developer model key
                        # enters the actual Worker child. Its only DB role is AI.
                        allowed=('PATH','SystemRoot','WINDIR','TEMP','TMP','USERPROFILE','APPDATA','LOCALAPPDATA','LANG','LC_ALL')
                        process_env={key:os.environ[key] for key in allowed if key in os.environ}
                        process_env.update(PYTHONUTF8='1',SUPPORTFLOW_LOOP_PROCESS_FIXTURE='1',
                            SUPPORTFLOW_RUNTIME_TEST_DSN=runtime_env['SUPPORTFLOW_RUNTIME_TEST_DSN'],
                            SUPPORTFLOW_LOOP_TEST_CUSTOMER_URL=base,SUPPORTFLOW_LOOP_TEST_CUSTOMER_KEY=secret,
                            SUPPORTFLOW_LOOP_TEST_MODEL_URL=model_base,SUPPORTFLOW_LOOP_TEST_MODEL_KEY=model_key)
                        crashed=subprocess.run([sys.executable,str(ROOT/'scripts/ticket_loop_process.py'),'crash',tenant],env=process_env,cwd=ROOT,
                            capture_output=True,text=True,encoding='utf-8',timeout=45,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
                        (ROOT/'.local/implementation/loop-crash-process.log').write_text(crashed.stdout+crashed.stderr,encoding='utf-8')
                        assert crashed.returncode==23 and 'MODEL_CHECKPOINT_COMMITTED' in crashed.stdout
                        held=store.get(tenant,run)
                        assert held['state']=='RUNNING' and held['model_calls']==1 and scenario['model_http_calls']==1
                        resumed=subprocess.run([sys.executable,str(ROOT/'scripts/ticket_loop_process.py'),'resume',tenant],env=process_env,cwd=ROOT,
                            capture_output=True,text=True,encoding='utf-8',timeout=45,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
                        (ROOT/'.local/implementation/loop-resume-process.log').write_text(resumed.stdout+resumed.stderr,encoding='utf-8')
                        assert resumed.returncode==0
                        result=store.get(tenant,run);assert result['attempt']==2
                    elif mode=='configured_worker':
                        other = RunInput('LP_UNASSIGNED', uuid4(), 'UNASSIGNED_WORKER', 1, message_id+2000,
                                         'visitor-session:'+str(session_id+2000), 'unassigned-worker')
                        queued = store.enqueue(other)
                        allowed = ('PATH','SystemRoot','WINDIR','TEMP','TMP','USERPROFILE','APPDATA','LOCALAPPDATA','LANG','LC_ALL')
                        worker_env = {key: os.environ[key] for key in allowed if key in os.environ}
                        worker_env.update(PYTHONUTF8='1', PYTHONPATH=str(ROOT/'ai-service'),
                            SUPPORTFLOW_AI_DATABASE_URL=runtime_env['SUPPORTFLOW_RUNTIME_TEST_DSN'],
                            SUPPORTFLOW_AI_CUSTOMER_RUNTIME_URL=base, SUPPORTFLOW_AI_CUSTOMER_SECRET=secret,
                            SUPPORTFLOW_AI_RUNTIME_CANONICAL_PLANNER_HISTORY='true',
                            SUPPORTFLOW_AI_RUNTIME_TICKET_MCP_URL=model_base.removesuffix('/v1')+'/mcp',
                            SUPPORTFLOW_AI_RUNTIME_TICKET_MCP_SECRET=mcp_key,
                            SUPPORTFLOW_AI_QWEN_API_KEY=model_key, SUPPORTFLOW_AI_QWEN_BASE_URL=model_base,
                            SUPPORTFLOW_AI_QWEN_MODEL='fixture-chat')
                        command = [sys.executable, '-m', 'ai.runtime.ticket_worker', '--tenant', tenant, '--provider', 'qwen', '--once']
                        options = dict(capture_output=True, text=True, encoding='utf-8',
                                       timeout=35, creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
                        with TemporaryDirectory(prefix='supportflow-worker-fixture-') as directory:
                            disabled = subprocess.run(command, cwd=directory,
                                env=worker_env | {'SUPPORTFLOW_AI_RUNTIME_API_ENABLED':'false'}, **options)
                            assert disabled.returncode == 1 and store.get(tenant, run)['state'] == 'PENDING'
                            configured = subprocess.run(command, cwd=directory,
                                env=worker_env | {'SUPPORTFLOW_AI_RUNTIME_API_ENABLED':'true'}, **options)
                        (ROOT/'.local/implementation/ticket-worker-process.log').write_text(
                            disabled.stdout+disabled.stderr+configured.stdout+configured.stderr, encoding='utf-8')
                        assert configured.returncode == 0 and model_key not in configured.stderr
                        assert store.get(other.tenant, queued['run_id']) == queued
                        result = store.get(tenant,run)
                        assert mcp_runs == [run], (len(mcp_runs), result['state'], result['error_code'])
                    else:result=worker.run_once(tenant)
                    if mode=='model503':
                        assert result['state']=='RETRY_WAIT' and result['model_calls']==1
                        transient = timeline.for_run(tenant, run)
                        assert transient['python_runtime']['run']['error_code'] == 'MODEL_UNAVAILABLE'
                        assert any(step['operation'] == 'model.chat' and step['state'] == 'UNKNOWN'
                                   for step in transient['python_runtime']['steps'])
                        timeline_cases['model_503_wait'] = transient
                        deadline=time.monotonic()+5
                        while time.monotonic()<deadline:
                            resumed=worker.run_once(tenant)
                            if resumed is not None:result=resumed;break
                            time.sleep(.05)
                        else:raise AssertionError('Loop retry did not become due')
                    expected={'normal':(None,2,5,10,0),'late_status':(None,2,6,11,0),'owner_revoked':('TICKET_REVOKED',2,5,9,0),
                              'foreign_parameter':('TOOL_ARGUMENTS_INVALID',1,2,4,0),'phantom_reference':('REPORT_EVIDENCE_INVALID',1,2,4,0),
                              'fake_write_claim':('PLANNER_DECISION_INVALID',1,2,4,0),'model503':(None,3,6,12,1),'turn_limit':('PLANNER_TURN_LIMIT',1,4,7,0),
                              'process_restart':(None,2,6,11,1),'configured_worker':(None,2,5,10,0)}[mode]
                    assert result['state']==('SUCCEEDED' if expected[0] is None else 'FAILED') and result['error_code']==expected[0]
                    assert (result['model_calls'],result['tool_calls'],result['step_count'],result['retry_count'])==expected[1:]
                    assert scenario['model_http_calls']==result['model_calls']
                    trace = store.trace_snapshot(tenant, run)
                    assert trace['run']['trace_id'] == source.trace_id
                    assert trace['run']['state'] == result['state'] and len(trace['steps']) == result['step_count']
                    assert store.trace_snapshot('LP_FOREIGN', run) is None
                    if mode in {'owner_revoked', 'model503', 'process_restart', 'configured_worker'}:
                        track = timeline.for_run(tenant, run)
                        assert track['binding_status'] == 'matched' and track['trace_id'] == source.trace_id
                        assert len(track['python_runtime']['steps']) == result['step_count']
                        assert track['python_runtime']['run']['state'] == result['state']
                        assert track['customer_delivery']['elapsed_to_receipt_ms'] >= 0
                        timeline_cases['visitor_finished' if mode == 'configured_worker' else mode] = track
                    if mode == 'configured_worker':
                        call_env = {name: os.environ[name] for name in ('PATH','SystemRoot','WINDIR','TEMP','TMP') if name in os.environ}
                        call_env.update({AI_DSN: trace_ai_dsn, CUSTOMER_DSN: trace_customer_dsn, 'PYTHONUTF8': '1'})
                        command = [sys.executable, '-m', 'ai.runtime.trace_timeline', '--trace-id', source.trace_id, '--tenant']
                        options = dict(cwd=ROOT/'ai-service', env=call_env, capture_output=True, encoding='utf-8', timeout=15)
                        visible = subprocess.run(command + [tenant], **options)
                        foreign = subprocess.run(command + ['LP_FOREIGN'], **options)
                        assert visible.returncode == foreign.returncode == 0, (visible.stderr, foreign.stderr)
                        assert json.loads(visible.stdout)['timelines'] == [json.loads(json.dumps(track, default=_metadata))]
                        assert json.loads(foreign.stdout)['timelines'] == []
                        assert all(secret_value not in visible.stdout for secret_value in (text, secret, model_key, 'PRIVATE LOOP', source.principal_ref))
                    with connect('ai_db') as db:
                        reports=[json.loads(row[0]) for row in db.execute("SELECT payload_json FROM agent_payload WHERE tenant_code=%s AND run_id=%s AND payload_schema='working-state-v1'",(tenant,run)).fetchall()]
                    with connect('customer_db') as db:
                        messages=db.execute('SELECT count(*) FROM session_message WHERE tenant_code=%s AND session_id=%s',(tenant,session_id)).fetchone()[0]
                        receipts=db.execute('SELECT count(*) FROM agent_result_receipt WHERE tenant_code=%s AND run_id=%s',(tenant,run)).fetchone()[0]
                        tickets=db.execute('SELECT count(*) FROM ticket WHERE tenant_code=%s',(tenant,)).fetchone()[0]
                        fact=db.execute('SELECT status FROM ticket WHERE id=%s',(ticket_id,)).fetchone()[0]
                    assert (messages,receipts,tickets)==(1,0,1)
                    if expected[0] is None:
                        assert len(reports)==1 and reports[0]['status']==fact==(4 if mode=='late_status' else 2)
                        assert reports[0]['text']==f"工单 {ticket_no} 当前状态：{'已解决' if fact==4 else '处理中'}。"
                        assert 'PRIVATE' not in json.dumps(reports)
                    else:assert reports==[]
                    observations.append(dict(case=mode,run_id=str(run),event_id=str(source.source_event_id),state=result['state'],error_code=result['error_code'],
                        model_http_calls=scenario['model_http_calls'],model_reservations=result['model_calls'],tool_reservations=result['tool_calls'],steps=result['step_count'],retries=result['retry_count'],
                        reports=len(reports),report_status=reports[0]['status'] if reports else None,actual_ticket_status=fact,source_messages=messages,result_receipts=receipts,tickets=tickets,
                        worker_process_restart=mode=='process_restart',configured_worker_process=mode=='configured_worker',
                        trace_id=trace['run']['trace_id'],
                        raw_assistant_bytes=scenario.get('raw_assistant_bytes'), canonical_assistant_bytes=scenario.get('canonical_assistant_bytes'),
                        mcp_calls=mcp_runs.count(run),
                        fixture_lease_seconds=3 if mode=='process_restart' else 30))
            report=ROOT/'.local/verification/runtime-ticket-loop.json';report.parent.mkdir(parents=True,exist_ok=True)
            report.write_text(json.dumps(dict(schema_version='ticket-loop-qualification-v5',mode='SYNTHETIC_MODEL_TCP_REAL_CUSTOMER_JAR_PG',cases=observations,
                source_enrollment='SIGNED_VISITOR_APPEND_FOR_CONFIGURED_WORKER',publication='REPORT_ONLY',provider_usage='SYNTHETIC_VALUES',performance='NOT_MEASURED',token_cost='NOT_MEASURED'),indent=2)+'\n',encoding='utf-8')
            print('Actual signed visitor append -> shadow Outbox -> scheduled AI delivery -> scoped worker CLI -> bounded MCP SSE/Java ticket read passed; 10 read-loop cases passed with synthetic model TCP and restricted PG',flush=True)
            approval_tracks = verify_ticket_approval(connect, base, child['SUPPORTFLOW_JWT_SECRET'], secret, store,
                                   runtime_env['SUPPORTFLOW_RUNTIME_TEST_DSN'], visitor_tenant=SHADOW_TENANT,
                                   append_visitor=lambda session_no, text: _send_shadow_message(
                                       connect, base, child['SUPPORTFLOW_CUSTOMER_REALTIME_SECRET'], store, session_no, text),
                                   timeline=timeline)
            timeline_cases.update(approval_tracks)
            (report.parent/'runtime-cross-service-trace.json').write_text(json.dumps({
                'schema_version': 'cross-service-trace-qualification-v1',
                'mode': 'REAL_JAVA_AI_POSTGRES_SYNTHETIC_PROVIDER', 'cases': timeline_cases},
                default=_metadata, indent=2)+'\n', encoding='utf-8')
            print(f'Cross-service read-only timeline: {len(timeline_cases)} snapshots and tenant CLI isolation passed', flush=True)
            from knowledge_tool_fixture import verify_knowledge_tools
            verify_knowledge_tools(connect,base,secret,store)
            from ticket_creation_api_fixture import verify_ticket_creation_api
            verify_ticket_creation_api(connect,base,secret,store)
            from ticket_write_tool_fixture import verify_ticket_write_tool
            verify_ticket_write_tool(connect,base,secret,store)
        except BaseException:
            failed=True;raise
        finally:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
            if failed:
                from security.service_diagnostics import capture
                capture(logs,ROOT/'.local/verification/loop-service-errors.json','runtime-ticket-loop')
