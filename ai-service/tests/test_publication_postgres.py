"""Actual WRITE/READ nesting and fences. Reply service is a protocol Stub here."""
import json
import os

import httpx
import psycopg
import pytest

from ai.runtime.publication import TicketReplyHandler
from ai.runtime.result import ResultClient
from ai.runtime.worker import RuntimeWorker
from ai.runtime.types import Limits
from test_runtime_store_postgres import store,request,admin
from test_ticket_loop import fixture,QUERY,REPORT
from test_chat_transport import response
from test_runtime_source import KEY


pytestmark=pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),reason='requires disposable runtime PostgreSQL')


def setup(store,mode='normal',limits=None,actions=None):
    source=request();row=store.enqueue(source,limits or Limits())
    loop,_,reads,models,reports,state=fixture(actions or [QUERY,REPORT],source_input=source,run_id=row['run_id'])
    calls=[];posted=[];committed=[]
    def server(req):
        calls.append(req.method)
        if req.method=='POST':
            posted.append(json.loads(req.content))
            if mode=='denied':return response(b'private authorization detail',status=401)
            if mode!='absent':committed.append(1)
            if mode=='cancel':store.cancel(source.tenant,row['run_id'])
            if mode in ('lost_ack','absent','lookup_down'):raise httpx.ReadTimeout('synthetic uncertain write')
        if req.method=='GET':
            if mode=='lookup_down':return response(b'private lookup outage',status=503)
            if not committed:return response({'code':40403,'message':'not observed','data':None},status=404)
        body=posted[0]
        return response(dict(code=0,message='ok',data=dict(schema_version='reply-v1',operation='session.reply',tenant_code=source.tenant,
            source_event_id=str(source.source_event_id),run_id=str(row['run_id']),snapshot_id=body['snapshot_id'],arguments_sha256=body['arguments_sha256'],
            message_id='123',message_no='BOT_MSG_1',seq='2')))
    handler=TicketReplyHandler(loop,ResultClient('http://customer.test',KEY,transport=httpx.MockTransport(server)))
    return source,row,handler,calls,posted,committed,reads,models,state


def worker(store,handler):return RuntimeWorker(store,{'runtime-v1':handler},'report-publisher')


def test_verified_report_commits_write_step_and_returns_opaque_business_reference(store):
    source,row,handler,calls,posted,committed,reads,models,state=setup(store)
    result=worker(store,handler).run_once(source.tenant)
    assert result['state']=='SUCCEEDED' and result['result_ref']['outcome']=='reply-committed'
    assert result['result_ref']['business_ref']=='message:123' and calls==['POST'] and committed==[1]
    assert (result['model_calls'],result['tool_calls'],result['step_count'])==(2,6,11)
    writes=[s for s in store.steps(source.tenant,row['run_id']) if s['effect']=='WRITE']
    assert len(writes)==1 and writes[0]['state']=='SUCCEEDED' and posted[0]['content']=='工单 TK-1 当前状态：处理中。'


def test_clarification_uses_the_same_constrained_publication_protocol(store):
    source,row,handler,calls,posted,committed,reads,models,state=setup(store,actions=[{'action':'clarify'}])
    result=worker(store,handler).run_once(source.tenant)
    assert result['state']=='SUCCEEDED' and calls==['POST'] and posted[0]['content']=='请提供需要查询的工单编号。'
    assert (result['model_calls'],result['tool_calls'],result['step_count'])==(1,3,6)


def test_lost_write_ack_uses_one_budgeted_receipt_query_and_no_second_post(store):
    source,row,handler,calls,posted,committed,reads,models,state=setup(store,'lost_ack')
    result=worker(store,handler).run_once(source.tenant)
    assert result['state']=='SUCCEEDED' and calls==['POST','GET'] and committed==[1]
    assert (result['model_calls'],result['tool_calls'],result['step_count'])==(2,7,12)
    writes=[s for s in store.steps(source.tenant,row['run_id']) if s['effect']=='WRITE'];assert writes[0]['state']=='SUCCEEDED'


@pytest.mark.parametrize('mode',['absent','lookup_down','denied'])
def test_unconfirmed_or_denied_write_is_held_unknown_without_automatic_repost(store,mode):
    source,row,handler,calls,posted,committed,reads,models,state=setup(store,mode)
    result=worker(store,handler).run_once(source.tenant)
    assert result['state']=='FAILED' and result['error_code']=='SIDE_EFFECT_UNKNOWN' and result['retry_count']==0
    assert calls==(['POST'] if mode=='denied' else ['POST','GET']) and len(posted)==1
    writes=[s for s in store.steps(source.tenant,row['run_id']) if s['effect']=='WRITE'];assert writes[0]['state']=='UNKNOWN'
    # The next source event cannot run past an unconfirmed side effect.
    next_row=store.enqueue(request(seq=2));assert store.claim_next(source.tenant,'next-worker') is None
    assert store.get(source.tenant,next_row['run_id'])['state']=='PENDING'


def test_receipt_recovery_cannot_bypass_tool_budget_or_erase_uncertain_write_state(store):
    source,row,handler,calls,posted,committed,reads,models,state=setup(store,'lost_ack',Limits(max_tool_calls=6))
    result=worker(store,handler).run_once(source.tenant)
    assert result['state']=='FAILED' and result['error_code']=='SIDE_EFFECT_UNKNOWN'
    assert result['tool_calls']==6 and result['step_count']==11 and calls==['POST'] and committed==[1]
    assert any(s['effect']=='WRITE' and s['state']=='UNKNOWN' for s in store.steps(source.tenant,row['run_id']))


def test_cancelled_lease_cannot_mark_a_late_confirmed_business_reply_as_runtime_success(store):
    source,row,handler,calls,posted,committed,reads,models,state=setup(store,'cancel')
    result=worker(store,handler).run_once(source.tenant)
    assert result['state']=='FAILED' and result['error_code']=='SIDE_EFFECT_UNKNOWN' and committed==[1] and calls==['POST']
    assert any(s['effect']=='WRITE' and s['state']=='UNKNOWN' for s in store.steps(source.tenant,row['run_id']))


class LostStepAck:
    def __init__(self,store):self.actual=store;self.once=True
    def __getattr__(self,name):return getattr(self.actual,name)
    def complete_step(self,*args):
        result=self.actual.complete_step(*args)
        if self.once and args[2]=='SUCCEEDED' and args[3].get('operation')=='session.reply':
            self.once=False;raise psycopg.OperationalError('synthetic lost successful step acknowledgement')
        return result


def test_committed_write_checkpoint_resumes_by_receipt_without_rerunning_changed_report(store):
    source,row,handler,calls,posted,committed,reads,models,state=setup(store)
    runner=worker(LostStepAck(store),handler);assert runner.run_once(source.tenant)['state']=='RETRY_WAIT'
    read_count=len(reads);state['owner']=False
    result=runner.run_once(source.tenant)
    assert result['state']=='SUCCEEDED' and calls==['POST','GET'] and len(posted)==1 and len(reads)==read_count and len(models)==2
    assert (result['model_calls'],result['tool_calls'],result['step_count'],result['retry_count'])==(2,7,12,1)


def test_corrupt_committed_write_reference_does_not_trigger_new_report_or_post(store):
    source,row,handler,calls,posted,committed,reads,models,state=setup(store)
    runner=worker(LostStepAck(store),handler);assert runner.run_once(source.tenant)['state']=='RETRY_WAIT'
    with admin() as db:
        db.execute("UPDATE agent_step SET result_ref=jsonb_set(result_ref,'{business_ref}','\"message:999\"') WHERE kind='TOOL' AND effect='WRITE'")
    result=runner.run_once(source.tenant)
    assert result['state']=='FAILED' and result['error_code']=='REPLY_CHECKPOINT_INVALID' and len(posted)==1 and len(models)==2
