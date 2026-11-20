"""Real result SDK -> customer JAR -> AI authority -> restricted PostgreSQL, synthetic data only."""
import asyncio
from contextlib import contextmanager
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import threading
import time
from uuid import uuid4

import httpx
import psycopg
from psycopg.rows import dict_row

from local_env import runtime_environment

ROOT=Path(__file__).resolve().parents[1]


@contextmanager
def lost_ack_proxy(base):
    evidence=dict(calls=0,committed_at=None,error=None)
    stop=threading.Event()
    class Handler(BaseHTTPRequestHandler):
        protocol_version='HTTP/1.1'
        def log_message(self,*args): pass
        def do_POST(self):
            try:
                evidence['calls']+=1
                body=self.rfile.read(int(self.headers['Content-Length']))
                assert len(body)<=65536
                headers={key:self.headers[key] for key in ('X-Tenant-Code','X-SupportFlow-Customer-Auth','X-Request-Id','Content-Type')}
                with httpx.Client(trust_env=False,timeout=8) as client:
                    response=client.post(base+self.path,content=body,headers=headers)
                assert response.status_code==200
                evidence['committed_at']=time.monotonic()
                self.close_connection=True
                self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(response.content)));self.send_header('Connection','close');self.end_headers()
                # Allow cold Java/authority startup before the client deadline;
                # the response body remains withheld beyond that total deadline.
                stop.wait(10)
                try:self.wfile.write(response.content)
                except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):pass
            except BaseException as failure:evidence['error']=type(failure).__name__
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler);server.daemon_threads=True
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    try:yield f'http://127.0.0.1:{server.server_port}',evidence
    finally:
        stop.set();server.shutdown();server.server_close();thread.join(timeout=3)


def verify_results(connect,environment,passwords,secret,ai_base,runtime_environment_values):
    sys.path.insert(0,str(ROOT/'ai-service'))
    from ai.runtime.source import SourceClient
    from ai.runtime.result import ResultClient
    from ai.runtime.reply import arguments_hash
    from ai.runtime.store import RuntimeStore
    from ai.runtime.types import RunInput,Lease
    from ai.runtime.worker import TransientFailure,PermanentFailure
    store=RuntimeStore(lambda:psycopg.connect(runtime_environment_values['SUPPORTFLOW_RUNTIME_TEST_DSN'],row_factory=dict_row))
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1]
    child={key:value for key,value in os.environ.items() if not key.startswith('SUPPORTFLOW_')}
    child.update(SUPPORTFLOW_DB_HOST='127.0.0.1',SUPPORTFLOW_DB_PORT=environment['SUPPORTFLOW_DB_PORT'],
                 SUPPORTFLOW_CUSTOMER_DB_PASSWORD=passwords['CUSTOMER'],SUPPORTFLOW_CUSTOMER_PLATFORM_DB_PASSWORD=passwords['CUSTOMER_PLATFORM'],
                 SUPPORTFLOW_AI_CUSTOMER_SECRET=secret,SUPPORTFLOW_JWT_SECRET=secrets.token_urlsafe(48),
                 SUPPORTFLOW_RUSTFS_SECRET_KEY=secrets.token_urlsafe(32),SUPPORTFLOW_BILLING_USAGE_REPORT_INITIAL_DELAY_MS='600000')
    child=runtime_environment('customer-service',child)
    java=Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java')
    jar,=(ROOT/'backend/supportflow-customer-service/target').glob('*.jar')
    logs=ROOT/'.local/runtime-result-service';logs.mkdir(parents=True,exist_ok=True)
    with (logs/'customer-service.log').open('wb') as log:
        process=subprocess.Popen([str(java),'-Xms32m','-Xmx384m','-jar',str(jar),f'--server.port={port}',
            '--supportflow.bot.runtime-source-enabled=true','--supportflow.bot.runtime-result-enabled=true',
            *[f'--supportflow.ai.{name}-base-url={ai_base}' for name in ('bot','qa','kb','vision','speech')]],
            cwd=ROOT,env=child,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
        failed=False
        try:
            base=f'http://127.0.0.1:{port}'
            with httpx.Client(trust_env=False,timeout=1) as http:
                deadline=time.monotonic()+60
                while time.monotonic()<deadline:
                    if process.poll() is not None:raise RuntimeError('Disposable result service exited')
                    try:
                        if http.get(base+'/actuator/health/readiness').status_code==200:break
                    except httpx.HTTPError:pass
                    time.sleep(.25)
                else:raise RuntimeError('Disposable result service not ready')
            observations=[]
            async def qualify():
                source_api,result_api=SourceClient(base,secret),ResultClient(base,secret)
                for case in range(2):
                    # Fresh per-case sources after JAR readiness keep the real 120s
                    # admission deadline intact. Admin is fixture setup only;
                    # enqueue/claim/steps use the real restricted AI role.
                    session_id=12_900_001+case*10;message_id=session_id+1;event=uuid4()
                    value=RunInput('RESULT_A',event,'RESULT_JAR_'+str(case),1,message_id,'visitor-session:'+str(session_id),'result-jar-'+str(case))
                    run=store.enqueue(value)['run_id']
                    with connect('customer_db') as db:
                        db.execute("INSERT INTO session(id,tenant_code,session_no,channel_id,status,last_msg_seq,start_time) VALUES(%s,'RESULT_A',%s,1,2,1,CURRENT_TIMESTAMP)",(session_id,value.session_no))
                        db.execute("INSERT INTO session_message(id,tenant_code,session_id,msg_no,seq,msg_type,sender_type,visible_to,content,send_time) VALUES(%s,'RESULT_A',%s,%s,1,1,1,1,'synthetic result source',CURRENT_TIMESTAMP)",(message_id,session_id,'RESULT_SOURCE_'+str(case)))
                        db.execute("""INSERT INTO agent_event_outbox(tenant_code,event_id,run_id,message_id,session_id,session_no,event_seq,principal_ref,trace_id,runtime_version,state,attempts,fence,delivered_at)
                            VALUES('RESULT_A',%s,%s,%s,%s,%s,1,%s,%s,'runtime-v1','DELIVERED',1,1,clock_timestamp())""",
                            (event,run,message_id,session_id,value.session_no,value.principal_ref,value.trace_id))
                    snapshot=await source_api.capture(value,run)
                    lease=Lease.from_run(store.claim_next(value.tenant,'reply-worker'))
                    assert lease.run_id==run
                    text='synthetic verified reply '+str(case)
                    digest=arguments_hash(value,run,snapshot.snapshot_id,text)
                    store.reserve_step(lease,'reply-1','TOOL','WRITE',dict(operation='session.reply',resource_ref=value.principal_ref,
                        input_ref=str(snapshot.snapshot_id),arguments_sha256=digest,version='reply-v1'))
                    assert await result_api.receipt(value,run) is None
                    if case==0:
                        receipt=await result_api.reply(value,lease,snapshot.snapshot_id,text)
                        assert receipt==await result_api.reply(value,lease,snapshot.snapshot_id,text)
                        try:await result_api.reply(value,lease,snapshot.snapshot_id,text+' changed')
                        except PermanentFailure as failure:assert failure.code=='REPLY_CONFLICT'
                        else:raise AssertionError('Changed committed reply was accepted')
                        store.complete_step(lease,'reply-1','SUCCEEDED',{'business_ref':'message:'+str(receipt.message_id)})
                        store.finish(lease,{'business_ref':'message:'+str(receipt.message_id)})
                        assert receipt==await result_api.reply(value,lease,snapshot.snapshot_id,text)
                        assert await result_api.receipt(replace(value,tenant='RESULT_B'),run) is None
                        try:await ResultClient(base,'wrong-pair-'+'0'*40).receipt(value,run)
                        except PermanentFailure as failure:assert failure.code=='REPLY_AUTHORIZATION_DENIED'
                        else:raise AssertionError('Invalid pair credential read a result')
                    else:
                        with lost_ack_proxy(base) as (proxy,evidence):
                            try:await ResultClient(proxy,secret,deadline=6).reply(value,lease,snapshot.snapshot_id,text)
                            except TransientFailure as failure:
                                assert failure.code=='REPLY_TIMEOUT'
                                assert evidence['committed_at'] is not None and evidence['committed_at']<=time.monotonic()
                            else:raise AssertionError('Lost receipt did not time out')
                            assert evidence['calls']==1 and evidence['error'] is None
                        # Runtime does not pretend cancellation undid an uncertain
                        # write. Receipt lookup can prove the business commit.
                        cancelled=store.cancel(value.tenant,run)
                        assert cancelled['error_code']=='SIDE_EFFECT_UNKNOWN'
                        receipt=await result_api.receipt(value,run,expected_snapshot_id=snapshot.snapshot_id,expected_hash=digest)
                        assert receipt is not None
                        assert receipt==await result_api.reply(value,lease,snapshot.snapshot_id,text)
                        assert store.get(value.tenant,run)['state']=='FAILED'  # No fabricated runtime reconciliation.
                    assert receipt==await result_api.receipt(value,run,expected_snapshot_id=snapshot.snapshot_id,expected_hash=digest)
                    with connect('customer_db') as db:
                        receipts=db.execute("SELECT count(*),min(message_id),min(message_seq) FROM agent_result_receipt WHERE tenant_code=%s AND run_id=%s",(value.tenant,run)).fetchone()
                        messages=db.execute("SELECT count(*) FROM session_message WHERE tenant_code=%s AND session_id=%s",(value.tenant,session_id)).fetchone()
                        message_fact=db.execute("SELECT sender_type,visible_to,content FROM session_message WHERE id=%s",(receipt.message_id,)).fetchone()
                        assert receipts==(1,receipt.message_id,2) and messages==(2,) and message_fact==(3,1,text)
                    actual=store.get(value.tenant,run)
                    assert (actual['step_count'],actual['model_calls'],actual['tool_calls'],actual['retry_count'])==(1,0,1,0)
                    observations.append(dict(case='normal_terminal_ack' if case==0 else 'lost_committed_ack',
                        run_id=str(run),event_id=str(event),snapshot_id=str(snapshot.snapshot_id),message_id=str(receipt.message_id),
                        arguments_sha256=digest,sequence=receipt.sequence,runtime_state=actual['state'],error_code=actual['error_code'],
                        step_count=actual['step_count'],model_calls=actual['model_calls'],tool_budget_used=actual['tool_calls'],
                        retry_budget_used=actual['retry_count'],reply_receipts=receipts[0],session_messages=messages[0],
                        reply_sender=message_fact[0],reply_visibility=message_fact[1],
                        automatic_runtime_reconciliation=False))
            asyncio.run(qualify())
            report=ROOT/'.local/verification/runtime-results.json';report.parent.mkdir(parents=True,exist_ok=True)
            report.write_text(json.dumps(dict(format='runtime-results-v1',mode='synthetic_real_services_mock_models',
                cases=observations,performance='NOT_MEASURED',token_cost='NOT_MEASURED'),indent=2)+'\n',encoding='utf-8')
            print('Real result SDK -> customer JAR -> AI authority -> restricted PG: 2 reply cases passed; one message/receipt each; lost committed acknowledgement recovered by query; terminal retry only confirmed existing result; UNKNOWN runtime not auto-reconciled',flush=True)
        except BaseException:
            failed=True;raise
        finally:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
            if failed:
                from security.service_diagnostics import capture
                capture(logs,ROOT/'.local/verification/result-service-errors.json','runtime-result')
