"""Host-selected knowledge tool through real Worker/embedding TCP/customer JAR/PG."""
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
from pathlib import Path
from threading import Thread
import time
from uuid import uuid4

ROOT=Path(__file__).resolve().parents[1]


@contextmanager
def embedding_server(scenarios):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            case=scenarios.get(self.headers.get('Authorization',''));length=int(self.headers.get('Content-Length','0'))
            if case is None or self.path!='/v1/embeddings' or not 0<length<=262144:
                self.send_response(403);self.send_header('Content-Length','0');self.end_headers();return
            body=json.loads(self.rfile.read(length));case['embedding_http_calls']+=1
            assert body==dict(model='fixture-embedding',input=['synthetic knowledge lookup'],dimensions=1024,encoding_format='float')
            failed=case['mode']=='model503' and case['embedding_http_calls']==1
            value={'error':'synthetic unavailable'} if failed else dict(object='list',model='fixture-embedding',
                data=[dict(object='embedding',index=0,embedding=[.25]+[0.0]*1023)],usage=dict(prompt_tokens=8,total_tokens=8))
            raw=json.dumps(value).encode();self.send_response(503 if failed else 200)
            self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
        def log_message(self,*_):pass
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=Thread(target=lambda:server.serve_forever(poll_interval=.01),daemon=True);thread.start()
    try:yield f'http://127.0.0.1:{server.server_port}/v1'
    finally:server.shutdown();server.server_close();thread.join(timeout=2)


def verify_knowledge_tools(connect,base,secret,store):
    from ai.runtime.embeddings import EmbeddingProfile,RuntimeEmbeddingClient
    from ai.runtime.knowledge import KnowledgeClient
    from ai.runtime.knowledge_tools import knowledge_search_tool,VERSION
    from ai.runtime.source import SourceClient
    from ai.runtime.tools import ToolCall,ToolExecutor,ToolRegistry
    from ai.runtime.types import RunInput,Limits
    from ai.runtime.worker import RuntimeWorker,TransientFailure

    observations=[];scenarios={}
    with embedding_server(scenarios) as model_base:
        for index,mode in enumerate(('normal_resume','offline_resume','source_takeover','model_budget0','refresh_budget3','empty_scope','model503')):
            tenant='KT_'+chr(ord('A')+index);session_id=19_200_001+index*10;message_id=session_id+1;doc_id=session_id+2
            source=RunInput(tenant,uuid4(),'KNOWLEDGE_JAR_'+str(index),1,message_id,'visitor-session:'+str(session_id),'knowledge-'+str(index))
            limits=Limits(max_model_calls=0) if mode=='model_budget0' else Limits(max_tool_calls=3) if mode=='refresh_budget3' else Limits()
            run=store.enqueue(source,limits)['run_id']
            with connect('customer_db') as db:
                db.execute("INSERT INTO session(id,tenant_code,session_no,channel_id,status,last_msg_seq,start_time) VALUES(%s,%s,%s,1,2,1,CURRENT_TIMESTAMP)",(session_id,tenant,source.session_no))
                db.execute("INSERT INTO session_message(id,tenant_code,session_id,msg_no,seq,msg_type,sender_type,visible_to,content,send_time) VALUES(%s,%s,%s,%s,1,1,1,1,'synthetic current knowledge source',CURRENT_TIMESTAMP)",
                           (message_id,tenant,session_id,'KT_SOURCE_'+str(index)))
                db.execute("""INSERT INTO agent_event_outbox(tenant_code,event_id,run_id,message_id,session_id,session_no,event_seq,principal_ref,trace_id,runtime_version,state,attempts,fence,delivered_at)
                    VALUES(%s,%s,%s,%s,%s,%s,1,%s,%s,'runtime-v1','DELIVERED',1,1,clock_timestamp())""",
                           (tenant,source.source_event_id,run,message_id,session_id,source.session_no,source.principal_ref,source.trace_id))
                for identity,owner,status,text in [(doc_id,tenant,3,'synthetic public FAQ'),(doc_id+1,tenant,1,'PRIVATE DRAFT'),(doc_id+2,'KT_FOREIGN',3,'PRIVATE FOREIGN')]:
                    db.execute("INSERT INTO kb_document(id,tenant_code,doc_no,title,status,index_status) VALUES(%s,%s,%s,'synthetic FAQ',%s,3)",(identity,owner,'KT_DOC_'+str(identity),status))
                    db.execute("INSERT INTO kb_chunk(id,tenant_code,doc_id,chunk_no,content,embedding,embedding_model) VALUES(%s,%s,%s,0,%s,%s::vector,'fixture:fixture-embedding')",
                               (identity,owner,identity,text,json.dumps([.25]+[0.0]*1023)))
            model_key='fixture-knowledge-'+tenant+'-credential'
            scenario=dict(mode=mode,embedding_http_calls=0,source_sdk_calls=0,knowledge_sdk_calls=0)
            scenarios['Bearer '+model_key]=scenario;outputs=[]
            class CountedSource(SourceClient):
                async def capture(self,*args,**kwargs):scenario['source_sdk_calls']+=1;return await super().capture(*args,**kwargs)
            class CountedKnowledge(KnowledgeClient):
                async def search(self,*args,**kwargs):scenario['knowledge_sdk_calls']+=1;return await super().search(*args,**kwargs)
            def handler(ctx):
                embedding=RuntimeEmbeddingClient(EmbeddingProfile(tenant,'knowledge-fixture-v1','fixture','fixture-embedding',model_base,model_key,timeout_seconds=3))
                tool=knowledge_search_tool(ctx,CountedSource(base,secret),CountedKnowledge(base,secret),embedding)
                executor=ToolExecutor(ToolRegistry([tool]),frozenset({'knowledge:read'}))
                value=executor.execute(ctx,'knowledge-1',ToolCall('search_knowledge',VERSION,
                    dict(query='synthetic knowledge lookup',doc_ids=[] if mode=='empty_scope' else None)))
                assert 'PRIVATE' not in json.dumps(value) and all(hit['doc_id']==str(doc_id) for hit in value['hits'])
                outputs.append(value)
                if mode.endswith('_resume') or mode in ('source_takeover','refresh_budget3'):
                    if len(outputs)==1:raise TransientFailure('AFTER_KNOWLEDGE',delay_seconds=0)
                return {'outcome':'knowledge-verified'}
            first=RuntimeWorker(store,{'runtime-v1':handler},'knowledge-actual-first').run_once(tenant)
            if mode=='offline_resume':
                with connect('customer_db') as db:db.execute('UPDATE kb_document SET status=4 WHERE id=%s',(doc_id,))
            elif mode=='source_takeover':
                with connect('customer_db') as db:db.execute('UPDATE session SET agent_id=12345 WHERE id=%s',(session_id,))
            result=first
            if first['state']=='RETRY_WAIT':
                resumed=RuntimeWorker(store,{'runtime-v1':handler},'knowledge-actual-resumed');deadline=time.monotonic()+6
                while time.monotonic()<deadline:
                    result=resumed.run_once(tenant)
                    if result is not None:break
                    time.sleep(.05)
                else:raise AssertionError('Knowledge retry did not become ready naturally')
            expected={'source_takeover':('FAILED','SOURCE_REVOKED',3,1,2,1,1),'model_budget0':('FAILED','BUDGET_EXHAUSTED',2,0,1,0,0),
                'refresh_budget3':('FAILED','BUDGET_EXHAUSTED',3,1,2,1,1),'empty_scope':('SUCCEEDED',None,2,0,1,0,0),
                'model503':('SUCCEEDED',None,4,2,2,1,2)}.get(mode,('SUCCEEDED',None,4,1,2,2,1))
            assert (result['state'],result['error_code'],result['tool_calls'],result['model_calls'],scenario['source_sdk_calls'],scenario['knowledge_sdk_calls'],scenario['embedding_http_calls'])==expected
            if mode=='offline_resume':assert outputs[0]['hits'] and outputs[1]['hits']==[]
            if mode=='normal_resume':assert len(outputs)==2 and outputs[0]['resource_version']==outputs[1]['resource_version']
            if mode=='empty_scope':assert outputs[0]['mode']=='empty-scope' and outputs[0]['request_sha256'] is None and outputs[0]['resource_version'] is None
            with connect('customer_db') as db:
                assert db.execute('SELECT count(*) FROM session_message WHERE session_id=%s',(session_id,)).fetchone()==(1,)
                assert db.execute('SELECT count(*) FROM agent_result_receipt WHERE tenant_code=%s AND run_id=%s',(tenant,run)).fetchone()==(0,)
            observations.append(dict(case=mode,state=result['state'],error_code=result['error_code'],tool_calls=result['tool_calls'],model_calls=result['model_calls'],
                step_count=result['step_count'],retry_count=result['retry_count'],source_sdk_calls=scenario['source_sdk_calls'],knowledge_sdk_calls=scenario['knowledge_sdk_calls'],
                embedding_http_calls=scenario['embedding_http_calls'],hit_counts=[len(item['hits']) for item in outputs],new_messages=0,result_receipts=0))
    report=ROOT/'.local/verification/runtime-knowledge-tools.json';report.parent.mkdir(parents=True,exist_ok=True)
    report.write_text(json.dumps(dict(schema_version='knowledge-tools-qualification-v1',mode='REAL_WORKER_EMBEDDING_TCP_CUSTOMER_JAR_RESTRICTED_PG',cases=observations,
        source_enrollment='EXPLICIT_DELIVERED_FIXTURE',model_planner='NOT_CONNECTED',semantic_quality='NOT_MEASURED',performance='NOT_MEASURED',usage='SYNTHETIC_8_TOKENS_NOT_COST'),indent=2)+'\n',encoding='utf-8')
    print('Actual knowledge Tool/Worker -> embedding TCP -> signed SDK/customer JAR/restricted PG: 7 cases passed; live publication/source checks, vector reuse on restart, shared budgets/model503 and empty scope; no business reply or ticket creation; no semantic/performance/cost claim',flush=True)
