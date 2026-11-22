"""Actual durable MODEL reservations/checkpoints; HTTP provider is a deterministic Stub."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import json
import os
from threading import Event
import asyncio

import httpx
import psycopg
import pytest

from ai.core.trace import bind_trace_id,get_trace_id
from ai.runtime.models import RuntimeChatClient
from ai.runtime.payloads import encode
from ai.runtime.types import Limits
from ai.runtime.worker import RuntimeWorker,TransientFailure
from test_runtime_store_postgres import store,request,admin
from test_runtime_models import profile,answer,MESSAGES
from test_chat_transport import response


pytestmark=pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),reason='requires disposable runtime PostgreSQL')


def client(callback,model_profile=None):return RuntimeChatClient(model_profile or profile(),transport=httpx.MockTransport(callback))
def worker(store,handler,owner='model-worker'):return RuntimeWorker(store,{'runtime-v1':handler},owner)
def payload_count():
    with admin() as db:return db.execute('SELECT count(*) AS n FROM agent_payload').fetchone()['n']
def ready():
    with admin() as db:db.execute("UPDATE agent_run SET ready_at=clock_timestamp() WHERE state='RETRY_WAIT'")


def test_model_success_and_repeated_access_have_one_http_one_reservation_one_atomic_body(store):
    row=store.enqueue(request());calls=[]
    api=client(lambda _:calls.append(1) or response(answer()))
    def handler(ctx):
        first=api.complete(ctx,'plan-1',MESSAGES);second=api.complete(ctx,'plan-1',MESSAGES)
        assert first==second and (first.input_tokens,first.output_tokens)==(13,7)
        return {'outcome':'model-verified'}
    result=worker(store,handler).run_once('TENANT_A')
    assert result['state']=='SUCCEEDED' and result['model_calls']==1 and result['step_count']==1 and calls==[1] and payload_count()==1
    assert 'synthetic private input' not in str(store.steps('TENANT_A',row['run_id']))
    with admin() as db:
        raw=db.execute('SELECT payload_json FROM agent_payload').fetchone()['payload_json']
        assert json.loads(raw)['usage']==dict(available=True,input_tokens=13,output_tokens=7)
        assert profile().api_key not in raw


def test_downstream_transient_read_does_not_repeat_successful_model_or_reset_counts(store):
    store.enqueue(request());calls=[];reads=[];api=client(lambda _:calls.append(1) or response(answer()))
    def handler(ctx):
        api.complete(ctx,'plan-1',MESSAGES)
        def read():
            reads.append(1)
            if len(reads)==1:raise TransientFailure('READ_TIMEOUT',delay_seconds=0)
            return {'resource_ref':'record-1'}
        return ctx.step('read-1','TOOL','READ',{'operation':'lookup'},read)
    runner=worker(store,handler);assert runner.run_once('TENANT_A')['state']=='RETRY_WAIT'
    result=runner.run_once('TENANT_A')
    assert result['state']=='SUCCEEDED' and calls==[1] and reads==[1,1]
    assert (result['model_calls'],result['tool_calls'],result['step_count'],result['retry_count'])==(1,2,3,1)


def test_new_worker_and_rotated_key_reuse_same_completed_model_policy_checkpoint(store):
    store.enqueue(request());calls=[];api=client(lambda _:calls.append(1) or response(answer()))
    def first(ctx):api.complete(ctx,'plan-1',MESSAGES);raise TransientFailure('AFTER_MODEL',delay_seconds=0)
    assert worker(store,first).run_once('TENANT_A')['state']=='RETRY_WAIT'
    rotated=client(lambda _:pytest.fail('completed model was repeated'),replace(profile(),api_key='rotated-synthetic-private-credential'))
    def resumed(ctx):assert rotated.complete(ctx,'plan-1',MESSAGES).input_tokens==13;return {'outcome':'done'}
    result=worker(store,resumed,'new-model-worker').run_once('TENANT_A')
    assert result['state']=='SUCCEEDED' and result['attempt']==2 and result['retry_count']==1 and result['model_calls']==1 and calls==[1]


@pytest.mark.parametrize('budget,expected',[(1,'FAILED'),(2,'SUCCEEDED')])
def test_provider_retry_consumes_original_model_budget_before_another_http_attempt(store,budget,expected):
    store.enqueue(request(),Limits(max_model_calls=budget));calls=[]
    def server(_):calls.append(1);return response(b'private-provider-error',status=503) if len(calls)==1 else response(answer())
    api=client(server)
    def handler(ctx):api.complete(ctx,'plan-1',MESSAGES);return {'outcome':'done'}
    runner=worker(store,handler);assert runner.run_once('TENANT_A')['state']=='RETRY_WAIT';ready()
    result=runner.run_once('TENANT_A')
    assert result['state']==expected and result['retry_count']==1 and len(calls)==budget and result['model_calls']==budget
    if expected=='FAILED':assert result['error_code']=='BUDGET_EXHAUSTED' and payload_count()==0
    else:assert payload_count()==1


def test_permanent_provider_denial_is_not_retried(store):
    store.enqueue(request());calls=[];api=client(lambda _:calls.append(1) or response(b'private auth echo',status=401))
    result=worker(store,lambda ctx:api.complete(ctx,'plan-1',MESSAGES)).run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']=='MODEL_AUTHORIZATION_DENIED'
    assert calls==[1] and result['retry_count']==0 and payload_count()==0


def test_model_diagnostics_use_the_persisted_trace_during_retry_and_worker_reuse(store, caplog):
    caplog.set_level('INFO', logger='ai.core.llm_chat')
    store.enqueue(replace(request(), trace_id='persisted-first'))
    store.enqueue(replace(request(tenant='TENANT_B'), trace_id='persisted-second'))
    requests = []

    def server(request):
        requests.append(request)
        if len(requests) == 1:
            return response(b'PRIVATE_PROVIDER_ERROR', status=503)
        return response(answer())

    clients = {tenant: client(server, profile(tenant=tenant)) for tenant in ('TENANT_A', 'TENANT_B')}

    def handler(context):
        clients[context.lease.tenant].complete(context, 'plan-1', MESSAGES)
        return {'outcome': 'done'}

    executor = worker(store, handler)
    with bind_trace_id('unrelated-caller'):
        assert executor.run_once('TENANT_A')['state'] == 'RETRY_WAIT'
        assert get_trace_id() == 'unrelated-caller'
        ready()
        assert executor.run_once('TENANT_B')['state'] == 'SUCCEEDED'
        assert get_trace_id() == 'unrelated-caller'
        resumed = executor.run_once('TENANT_A')
        assert (resumed['state'], resumed['model_calls'], resumed['retry_count']) == ('SUCCEEDED', 2, 1)
        assert get_trace_id() == 'unrelated-caller'

    assert len(requests) == 3 and get_trace_id() == ''
    assert all('X-Request-Id' not in request.headers for request in requests)
    logs = [message for message in caplog.messages if 'trace=' in message]
    assert [message.split('trace=')[1].split()[0] for message in logs] == [
        'persisted-first', 'persisted-second', 'persisted-first',
    ]
    assert 'code=MODEL_UNAVAILABLE status=503' in logs[0]
    assert 'PRIVATE_PROVIDER_ERROR' not in caplog.text


@pytest.mark.parametrize('fault',['length','credential'])
def test_unusable_or_sensitive_output_never_gets_a_successful_payload(store,fault):
    store.enqueue(request());data=answer()
    if fault=='length':data['choices'][0]['finish_reason']='length'
    else:data['choices'][0]['message']['content']=profile().api_key
    api=client(lambda _:response(data))
    result=worker(store,lambda ctx:api.complete(ctx,'plan-1',MESSAGES)).run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']=='MODEL_OUTPUT_INVALID' and payload_count()==0
    assert result['model_calls']==1 and result['retry_count']==0 and profile().api_key not in str(result)


def test_cancel_during_http_does_not_commit_late_model_output_or_refund_reservation(store):
    row=store.enqueue(request());entered,release=Event(),Event()
    async def server(_):
        entered.set()
        while not release.is_set():await asyncio.sleep(.01)
        return response(answer())
    api=client(server)
    def handler(ctx):api.complete(ctx,'plan-1',MESSAGES);return {'outcome':'must-not-finish'}
    with ThreadPoolExecutor(1) as pool:
        future=pool.submit(worker(store,handler).run_once,'TENANT_A')
        try:assert entered.wait(5);store.cancel('TENANT_A',row['run_id'])
        finally:release.set()
        result=future.result(timeout=10)
    assert result['state']=='CANCELLED' and result['model_calls']==1 and payload_count()==0


def test_model_payload_commit_lost_ack_is_recovered_without_second_http_attempt(store):
    store.enqueue(request());calls=[];api=client(lambda _:calls.append(1) or response(answer()))
    class LostAck:
        once=True
        def __getattr__(self,name):return getattr(store,name)
        def complete_payload_step(self,*args):
            value=store.complete_payload_step(*args)
            if self.once:self.once=False;raise psycopg.OperationalError('private commit acknowledgement detail')
            return value
    def handler(ctx):api.complete(ctx,'plan-1',MESSAGES);return {'outcome':'done'}
    runner=worker(LostAck(),handler);assert runner.run_once('TENANT_A')['state']=='RETRY_WAIT'
    result=runner.run_once('TENANT_A')
    assert result['state']=='SUCCEEDED' and calls==[1] and result['model_calls']==1 and result['step_count']==1 and payload_count()==1


@pytest.mark.parametrize('change',['profile','messages'])
def test_changed_model_policy_or_prompt_cannot_reuse_a_logical_checkpoint(store,change):
    store.enqueue(request());calls=[];api=client(lambda _:calls.append(1) or response(answer()))
    def first(ctx):api.complete(ctx,'plan-1',MESSAGES);raise TransientFailure('AFTER_MODEL',delay_seconds=0)
    assert worker(store,first).run_once('TENANT_A')['state']=='RETRY_WAIT'
    new_api=client(lambda _:pytest.fail('changed intent made another model call'),replace(profile(),base_url='http://other.test/v1')) if change=='profile' else api
    messages=MESSAGES if change=='profile' else MESSAGES+[{'role':'user','content':'changed context'}]
    result=worker(store,lambda ctx:new_api.complete(ctx,'plan-1',messages)).run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']=='EXECUTION_ERROR' and calls==[1] and result['model_calls']==1


@pytest.mark.parametrize('field,value',[('profile_ref','other-profile'),('usage',{'available':True,'input_tokens':True,'output_tokens':1}),
                                     ('content','synthetic-private-model-credential')])
def test_integrity_valid_but_semantically_changed_model_checkpoint_is_rejected(store,field,value):
    store.enqueue(request());calls=[];api=client(lambda _:calls.append(1) or response(answer()))
    def handler(ctx):api.complete(ctx,'plan-1',MESSAGES);raise TransientFailure('AFTER_MODEL',delay_seconds=0)
    runner=worker(store,handler);assert runner.run_once('TENANT_A')['state']=='RETRY_WAIT'
    with admin() as db:
        data=json.loads(db.execute('SELECT payload_json FROM agent_payload').fetchone()['payload_json']);data[field]=value
        raw,digest=encode('model-output-v1',data)
        db.execute('UPDATE agent_payload SET payload_json=%s,payload_hash=%s',(raw,digest))
    result=runner.run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']=='MODEL_CHECKPOINT_INVALID' and calls==[1] and result['model_calls']==1


def test_wrong_tenant_profile_fails_before_model_budget_or_transport(store):
    store.enqueue(request());api=client(lambda _:pytest.fail('foreign model profile reached provider'),profile(tenant='TENANT_B'))
    result=worker(store,lambda ctx:api.complete(ctx,'plan-1',MESSAGES)).run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']=='MODEL_PROFILE_DENIED'
    assert result['model_calls']==0 and result['step_count']==0 and payload_count()==0
