"""Actual customer JAR knowledge route; explicit fixture, no model or SDK tool yet."""
import hashlib
import hmac
import json
from pathlib import Path
import time

import httpx

ROOT=Path(__file__).resolve().parents[1]


def verify_knowledge_api(connect,base,secret,source,run):
    path=f'/api/customer/internal/runtime/events/{source.source_event_id}/runs/{run}/knowledge/search'
    vector=[.25]+[0.0]*1023
    with connect('customer_db') as db:
        snapshot,source_hash=db.execute('SELECT snapshot_id,payload_hash FROM agent_source_snapshot WHERE tenant_code=%s AND event_id=%s AND run_id=%s',
                                        (source.tenant,source.source_event_id,run)).fetchone()
        for doc,owner,status,index,model,content in [
            (17_900_001,source.tenant,3,3,'fixture:fixture-embedding','public synthetic knowledge'),
            (17_900_002,source.tenant,1,3,'fixture:fixture-embedding','PRIVATE DRAFT'),
            (17_900_003,'KNOW_FOREIGN',3,3,'fixture:fixture-embedding','PRIVATE FOREIGN'),
            (17_900_004,source.tenant,3,3,'local-hash','PRIVATE INCOMPATIBLE')]:
            db.execute("INSERT INTO kb_document(id,tenant_code,doc_no,title,status,index_status) VALUES(%s,%s,%s,'synthetic title',%s,%s)",
                       (doc,owner,'KAPI_'+str(doc),status,index))
            db.execute('INSERT INTO kb_chunk(id,tenant_code,doc_id,chunk_no,content,embedding,embedding_model) VALUES(%s,%s,%s,0,%s,%s::vector,%s)',
                       (doc,owner,doc,content,json.dumps(vector),model))
    body=dict(snapshot_id=str(snapshot),query_sha256='a'*64,embedding_model='fixture:fixture-embedding',vector=vector,top_k=5,doc_ids=None)
    def call(client,value=body,tenant=source.tenant,key=secret):
        raw=json.dumps(value,separators=(',',':')).encode();digest=hashlib.sha256(raw).hexdigest();expiry=str(int(time.time())+20)
        canonical='\n'.join(('supportflow-customer-runtime:v1','ai',expiry,'POST',path,'',tenant,digest))
        signature=hmac.new(key.encode(),canonical.encode(),hashlib.sha256).hexdigest()
        result=client.post(base+path,content=raw,headers={'X-Tenant-Code':tenant,'Content-Type':'application/json','Accept-Encoding':'identity',
            'X-Request-Id':'knowledge-api-qualification','X-SupportFlow-Customer-Auth':f'runtime:ai:{expiry}:{digest}:{signature}'})
        return result,digest
    with httpx.Client(trust_env=False,timeout=5) as client:
        result,digest=call(client);assert result.status_code==200
        data=result.json()['data'];assert len(data)==12 and data['request_sha256']==digest and data['source_hash']==source_hash
        assert data['policy_version']=='visitor-published-v1' and data['snapshot_id']==str(snapshot) and len(data['hits'])==1
        hit=data['hits'][0];assert hit['doc_id']=='17900001' and hit['snippet']=='public synthetic knowledge' and hit['score_ppm']==1_000_000
        assert 'PRIVATE' not in result.text
        sha=lambda value:hashlib.sha256(value.encode()).hexdigest()
        parts=['supportflow-knowledge-search:v1','visitor-published-v1',source.tenant,str(source.source_event_id),str(run),str(snapshot),source_hash,digest,'a'*64,'fixture:fixture-embedding','1',
               hit['doc_id'],hit['chunk_id'],sha(hit['title']),sha(hit['snippet']),str(hit['score_ppm']),hit['embedding_model']]
        assert data['resource_version']==sha('\n'.join(parts))
        result,_=call(client,body | {'doc_ids':[]});assert result.status_code==200 and result.json()['data']['hits']==[]
        result,_=call(client,body | {'doc_ids':[17_900_002,17_900_003]});assert result.status_code==200 and result.json()['data']['hits']==[]
        result,_=call(client,tenant='KNOW_FOREIGN');assert result.status_code==404 and result.json()['code']==40402
        result,_=call(client,key='wrong-knowledge-pair-'+'0'*40);assert result.status_code==401
        with connect('customer_db') as db:db.execute('UPDATE session SET agent_id=12345 WHERE session_no=%s AND tenant_code=%s',(source.session_no,source.tenant))
        try:
            result,_=call(client);assert result.status_code==404 and result.json()['code']==40402 and result.json()['data'] is None
        finally:
            with connect('customer_db') as db:db.execute('UPDATE session SET agent_id=NULL WHERE session_no=%s AND tenant_code=%s',(source.session_no,source.tenant))
    with connect('customer_db') as db:
        assert db.execute('SELECT count(*) FROM session_message WHERE session_id=(SELECT session_id FROM agent_event_outbox WHERE tenant_code=%s AND event_id=%s)',
                          (source.tenant,source.source_event_id)).fetchone()==(1,)
        assert db.execute('SELECT count(*) FROM agent_result_receipt WHERE tenant_code=%s AND run_id=%s',(source.tenant,run)).fetchone()==(0,)
    report=ROOT/'.local/verification/runtime-knowledge-api.json';report.parent.mkdir(parents=True,exist_ok=True)
    report.write_text(json.dumps(dict(schema_version='knowledge-api-qualification-v1',mode='REAL_CUSTOMER_JAR_RESTRICTED_PG',
        authorized_hits=1,empty_scope_hits=0,excluded_scope_hits=0,foreign_tenant_denied=True,wrong_key_denied=True,takeover_denied=True,
        request_and_projection_hash_verified=True,new_messages=0,result_receipts=0,model_calls=0,
        source_mode='EXISTING_DELIVERED_SOURCE_EXPLICIT_FIXTURE',agent_sdk_and_loop='NEXT_BATCH',performance='NOT_MEASURED'),indent=2)+'\n',encoding='utf-8')
    print('Actual signed knowledge POST -> customer JAR/restricted PG: published compatible facts only; scope/tenant/credential/takeover denials; request/projection hashes matched; no model or business writes; SDK/Registry next batch',flush=True)
