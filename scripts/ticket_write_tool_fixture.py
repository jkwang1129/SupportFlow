"""Real Worker/Registry write -> customer JAR -> AI authority/PG with TCP ACK faults."""
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
from pathlib import Path
from threading import Thread
from uuid import uuid4

import httpx
import psycopg

ROOT=Path(__file__).resolve().parents[1]


@contextmanager
def ticket_proxy(base,mode,on_commit,*,before_create=None):
    evidence=dict(sources=0,posts=0,gets=0,committed=False)
    class Handler(BaseHTTPRequestHandler):
        protocol_version='HTTP/1.1'
        def log_message(self,*_):pass
        def do_POST(self):self.forward('POST')
        def do_GET(self):self.forward('GET')
        def forward(self,method):
            body=self.rfile.read(int(self.headers.get('Content-Length','0')))
            ticket=self.path.endswith('/ticket-create')
            if ticket:evidence['posts' if method=='POST' else 'gets']+=1
            else:evidence['sources']+=1
            if ticket and method=='POST' and before_create is not None:
                before_create()
            if ticket and method=='POST' and mode=='absent':
                status=503;raw=b'{"code":503,"message":"synthetic no forward","data":null}'
            else:
                headers={key:self.headers[key] for key in ('X-Tenant-Code','X-SupportFlow-Customer-Auth','X-Request-Id','Content-Type')}
                with httpx.Client(trust_env=False,follow_redirects=False,timeout=8) as client:response=client.request(method,base+self.path,headers=headers,content=body)
                status=response.status_code;raw=response.content
                assert len(raw)<=131072
                if ticket and method=='POST' and status==200:
                    evidence['committed']=True
                    if mode=='cancel_after_commit':on_commit()
                    if mode in ('lost_ack','receipt_budget2'):
                        # Confirm the upstream commit before dropping the downstream acknowledgement.
                        self.close_connection=True
                        return
            self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)))
            self.send_header('Connection','close');self.end_headers();self.close_connection=True
            try:self.wfile.write(raw)
            except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):pass
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler);server.daemon_threads=True
    thread=Thread(target=lambda:server.serve_forever(poll_interval=.02),daemon=True);thread.start()
    try:yield f'http://127.0.0.1:{server.server_port}',evidence
    finally:server.shutdown();server.server_close();thread.join(timeout=3)


class LostStepAck:
    def __init__(self,actual):self.actual=actual;self.once=True
    def __getattr__(self,name):return getattr(self.actual,name)
    def complete_step(self,*args):
        result=self.actual.complete_step(*args)
        if self.once and args[2]=='SUCCEEDED' and args[3].get('operation')=='ticket.create':
            self.once=False;raise psycopg.OperationalError('synthetic successful write step ACK lost')
        return result


def verify_ticket_write_tool(connect,base,secret,store):
    from ai.runtime.reconciliation import TicketReconciler
    from ai.runtime.source import SourceClient
    from ai.runtime.ticket_creation import TicketCreationClient
    from ai.runtime.ticket_write_tool import ticket_create_tool
    from ai.runtime.tools import ToolCall,ToolExecutor,ToolRegistry
    from ai.runtime.types import RunInput,Limits
    from ai.runtime.worker import RuntimeWorker
    observations=[]
    modes=('normal','lost_ack','absent','receipt_budget2','tool_budget0','source_takeover','bad_parameters','committed_restart','cancel_after_commit','scope_denied')
    for index,mode in enumerate(modes):
        tenant='TW_'+chr(ord('A')+index);session_id=20_400_001+index*10;message_id=session_id+1;customer_id=session_id+2
        source=RunInput(tenant,uuid4(),'TICKET_WRITE_JAR_'+str(index),1,message_id,'visitor-session:'+str(session_id),'ticket-write-'+str(index))
        limits=Limits(max_tool_calls=0 if mode=='tool_budget0' else 2 if mode=='receipt_budget2' else 8)
        run=store.enqueue(source,limits)['run_id']
        with connect('customer_db') as db:
            db.execute("INSERT INTO customer(id,tenant_code,customer_no,name,ticket_count) VALUES(%s,%s,%s,'SYNTHETIC PRIVATE WRITE CUSTOMER',3)",(customer_id,tenant,'TW_CUSTOMER_'+str(index)))
            db.execute("INSERT INTO session(id,tenant_code,session_no,channel_id,status,last_msg_seq,customer_id,start_time) VALUES(%s,%s,%s,1,2,1,%s,CURRENT_TIMESTAMP)",(session_id,tenant,source.session_no,customer_id))
            db.execute("INSERT INTO session_message(id,tenant_code,session_id,msg_no,seq,msg_type,sender_type,visible_to,content,send_time) VALUES(%s,%s,%s,%s,1,1,1,1,'synthetic ticket write source',CURRENT_TIMESTAMP)",
                (message_id,tenant,session_id,'TW_SOURCE_'+str(index)))
            db.execute("""INSERT INTO agent_event_outbox(tenant_code,event_id,run_id,message_id,session_id,session_no,event_seq,principal_ref,trace_id,runtime_version,state,attempts,fence,delivered_at)
                VALUES(%s,%s,%s,%s,%s,%s,1,%s,%s,'runtime-v1','DELIVERED',1,1,clock_timestamp())""",
                (tenant,source.source_event_id,run,message_id,session_id,source.session_no,source.principal_ref,source.trace_id))
            if mode=='source_takeover':db.execute('UPDATE session SET agent_id=99 WHERE id=%s',(session_id,))
        with ticket_proxy(base,mode,lambda:store.cancel(tenant,run)) as (proxy,io):
            source_client=SourceClient(proxy,secret,deadline=3)
            write_client=TicketCreationClient(proxy,secret,deadline=3)
            executor=ToolExecutor(ToolRegistry([ticket_create_tool(source_client,write_client,timeout_seconds=5)]),frozenset() if mode=='scope_denied' else frozenset({'ticket:create'}))
            proposal=ToolCall('create_ticket','ticket-create-v1',dict(title='SYNTHETIC PRIVATE WRITE TITLE',content='synthetic original body\n原文 '))
            if mode=='bad_parameters':proposal.arguments['customer_id']=customer_id
            def handler(ctx):return executor.execute(ctx,'host-selected-call',proposal)
            worker=RuntimeWorker(LostStepAck(store) if mode=='committed_restart' else store,{'runtime-v1':handler},'actual-ticket-writer')
            result=worker.run_once(tenant)
            if mode=='committed_restart':
                assert result['state']=='RETRY_WAIT' and io['posts']==1
                with connect('customer_db') as db:db.execute('UPDATE session SET agent_id=99,status=4 WHERE id=%s',(session_id,))
                result=RuntimeWorker(store,{'runtime-v1':handler},'restarted-ticket-writer').run_once(tenant)
            expected={
                'normal':('SUCCEEDED',None,3,2,0,1,1,0),
                'lost_ack':('SUCCEEDED',None,4,3,0,1,1,1),
                'absent':('FAILED','SIDE_EFFECT_UNKNOWN',4,3,0,1,1,1),
                'receipt_budget2':('FAILED','SIDE_EFFECT_UNKNOWN',3,2,0,1,1,0),
                'tool_budget0':('FAILED','BUDGET_EXHAUSTED',1,0,0,0,0,0),
                'source_takeover':('FAILED','SOURCE_REVOKED',2,1,0,1,0,0),
                'bad_parameters':('FAILED','TOOL_ARGUMENTS_INVALID',0,0,0,0,0,0),
                'committed_restart':('SUCCEEDED',None,4,3,1,1,1,1),
                'cancel_after_commit':('FAILED','SIDE_EFFECT_UNKNOWN',3,2,0,1,1,0),
                'scope_denied':('FAILED','TOOL_SCOPE_DENIED',0,0,0,0,0,0),
            }[mode]
            assert (result['state'],result['error_code'],result['step_count'],result['tool_calls'],result['retry_count'],io['sources'],io['posts'],io['gets'])==expected,(mode,result,io)
            assert result['model_calls']==0
            with connect('customer_db') as db:
                tickets=db.execute('SELECT count(*) FROM ticket WHERE tenant_code=%s',(tenant,)).fetchone()[0]
                receipts=db.execute('SELECT count(*) FROM agent_ticket_receipt WHERE tenant_code=%s AND run_id=%s',(tenant,run)).fetchone()[0]
                events=db.execute('SELECT count(*) FROM ticket_event WHERE tenant_code=%s',(tenant,)).fetchone()[0]
                count=db.execute('SELECT ticket_count FROM customer WHERE id=%s',(customer_id,)).fetchone()[0]
                messages=db.execute('SELECT count(*) FROM session_message WHERE session_id=%s',(session_id,)).fetchone()[0]
            committed=int(io['committed']);assert (tickets,receipts,events,count,messages)==(committed,committed,committed,3+committed,1)
            steps=store.steps(tenant,run);writes=[step for step in steps if step['effect']=='WRITE']
            if result['error_code']=='SIDE_EFFECT_UNKNOWN':
                assert len(writes)==1 and writes[0]['state']=='UNKNOWN'
                later=store.enqueue(RunInput(tenant,uuid4(),source.session_no,2,message_id+100000,source.principal_ref,'held-next-source'))
                assert store.claim_next(tenant,'must-not-pass-unknown') is None and store.get(tenant,later['run_id'])['state']=='PENDING'
            elif committed:assert len(writes)==1 and writes[0]['state']=='SUCCEEDED'
            else:assert writes==[]
            assert 'PRIVATE' not in json.dumps(steps,default=str)+json.dumps(result,default=str)
            with connect('ai_db') as db:assert db.execute('SELECT count(*) FROM agent_payload WHERE tenant_code=%s AND run_id=%s',(tenant,run)).fetchone()[0]==0
            confirmation = None
            if result['error_code'] == 'SIDE_EFFECT_UNKNOWN':
                # A new trusted host observes the terminal task, then only uses the receipt GET.
                posts_before = io['posts']
                gets_before = io['gets']
                reconciler = TicketReconciler(store, TicketCreationClient(proxy, secret), 'qualification:ticket-audit')
                audit = reconciler.reconcile(tenant, run)
                after = store.get(tenant, run)
                assert io['posts'] == posts_before and io['gets'] == gets_before + 1
                if committed:
                    assert audit['ticket_id'] > 2**53 and audit['original_step_result'] == writes[0]['result_ref']
                    assert after == result | dict(error_code='SIDE_EFFECT_CONFIRMED', fence=result['fence'] + 1,
                                                  result_ref=after['result_ref'], updated_at=after['updated_at'])
                    assert reconciler.reconcile(tenant, run) == audit and io['gets'] == gets_before + 1
                    assert store.claim_next(tenant, 'after-ticket-confirmation')['run_id'] == later['run_id']
                    with connect('customer_db') as db:
                        assert db.execute('SELECT ticket_id,ticket_no FROM agent_ticket_receipt WHERE tenant_code=%s AND run_id=%s',
                                          (tenant,run)).fetchone() == (audit['ticket_id'], audit['ticket_no'])
                else:
                    assert audit is None and after == result and store.claim_next(tenant, 'still-held') is None
                confirmation = dict(audit_persisted=audit is not None, state=after['state'], error_code=after['error_code'], receipt_requests=1)
            observations.append(dict(case=mode,run_id=str(run),state=result['state'],error_code=result['error_code'],steps=result['step_count'],tools=result['tool_calls'],
                models=result['model_calls'],retries=result['retry_count'],source_requests=io['sources'],ticket_posts=io['posts'],receipt_gets=expected[-1],
                tickets=tickets,receipts=receipts,ticket_events=events,customer_ticket_count=count,source_messages=messages,
                write_state=writes[0]['state'] if writes else None, terminal_confirmation=confirmation))
    report=ROOT/'.local/verification/runtime-ticket-write-tool.json';report.parent.mkdir(parents=True,exist_ok=True)
    report.write_text(json.dumps(dict(schema_version='ticket-write-tool-qualification-v2',mode='SYNTHETIC_HOST_TOOL_REAL_JAVA_AI_PG_TCP_FAULTS',cases=observations,
        model_write_loop='NOT_IMPLEMENTED',automatic_source_enrollment=False,performance='NOT_MEASURED',token_cost='NOT_MEASURED'),indent=2)+'\n',encoding='utf-8')
    print('Actual Worker/WriteTool -> signed SDK/customer JAR -> AI authority/restricted PG: 10 cases passed; explicit terminal recovery confirmed 2 existing tickets with GET only; absent receipt kept the hold',flush=True)
