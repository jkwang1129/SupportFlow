"""Actual durable MODEL reservations; synthetic provider protocol and local TCP."""
from dataclasses import replace
import json
import os
import time

import httpx
import psycopg
import pytest

from ai.runtime.embeddings import RuntimeEmbeddingClient
from ai.runtime.models import RuntimeChatClient
from ai.runtime.payloads import encode
from ai.runtime.types import Limits
from ai.runtime.worker import RuntimeWorker,TransientFailure
from test_runtime_store_postgres import store,request,admin
from test_runtime_embeddings import profile,answer,TEXT
from test_runtime_models import profile as chat_profile,answer as chat_answer,MESSAGES
from test_chat_transport import response,local_provider

pytestmark=pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),reason='requires disposable runtime PostgreSQL')


def client(server,model_profile=None):return RuntimeEmbeddingClient(model_profile or profile(),transport=httpx.MockTransport(server))
def worker(store,handler):return RuntimeWorker(store,{'runtime-v1':handler},'embedding-worker')
def done(ctx,api):api.embed(ctx,'query-1',TEXT);return {'outcome':'embedding-verified'}
def payload_count():
    with admin() as db:return db.execute('SELECT count(*) AS n FROM agent_payload').fetchone()['n']


def next_ready(runner,tenant='TENANT_A'):
    deadline=time.monotonic()+4
    while time.monotonic()<deadline:
        value=runner.run_once(tenant)
        if value is not None:return value
        time.sleep(.05)
    raise AssertionError('Retry did not become ready naturally')


def test_success_and_cache_have_one_model_one_http_and_atomic_private_vector_usage(store):
    row=store.enqueue(request());calls=[];api=client(lambda _:calls.append(1) or response(answer()))
    def handler(ctx):
        first=api.embed(ctx,'query-1',TEXT)
        assert api.embed(ctx,'query-1',TEXT)==first and first.input_tokens==8
        return {'outcome':'embedding-verified'}
    result=worker(store,handler).run_once('TENANT_A')
    assert result['state']=='SUCCEEDED' and calls==[1] and (result['model_calls'],result['step_count'])==(1,1) and payload_count()==1
    assert TEXT not in str(store.steps('TENANT_A',row['run_id']))
    with admin() as db:
        raw=db.execute('SELECT payload_json FROM agent_payload').fetchone()['payload_json']
        value=json.loads(raw)
        assert len(value['vector'])==1024 and value['usage']==dict(available=True,input_tokens=8)
        assert profile().api_key not in raw and TEXT not in raw


@pytest.mark.parametrize('budget,expected',[(1,'FAILED'),(2,'SUCCEEDED')])
def test_embedding_and_chat_share_original_model_budget_without_a_free_second_call(store,budget,expected):
    store.enqueue(request(),Limits(max_model_calls=budget));calls=[]
    api=client(lambda _:calls.append('embedding') or response(answer()))
    chat=RuntimeChatClient(chat_profile(),transport=httpx.MockTransport(lambda _:calls.append('chat') or response(chat_answer())))
    def handler(ctx):api.embed(ctx,'query-1',TEXT);chat.complete(ctx,'plan-1',MESSAGES);return {'outcome':'done'}
    result=worker(store,handler).run_once('TENANT_A')
    assert result['state']==expected and result['model_calls']==budget and calls==(['embedding'] if budget==1 else ['embedding','chat'])
    if budget==1:assert result['error_code']=='BUDGET_EXHAUSTED'


def test_a_restarted_worker_with_rotated_key_reuses_the_committed_vector_and_counts(store):
    store.enqueue(request());calls=[];api=client(lambda _:calls.append(1) or response(answer()))
    def first(ctx):api.embed(ctx,'query-1',TEXT);raise TransientFailure('AFTER_EMBEDDING',delay_seconds=0)
    assert worker(store,first).run_once('TENANT_A')['state']=='RETRY_WAIT'
    rotated=client(lambda _:pytest.fail('embedding repeated'),replace(profile(),api_key='rotated-synthetic-embedding-credential'))
    result=worker(store,lambda ctx:done(ctx,rotated)).run_once('TENANT_A')
    assert result['state']=='SUCCEEDED' and result['model_calls']==1 and result['retry_count']==1 and result['attempt']==2 and calls==[1]


@pytest.mark.parametrize('budget,expected',[(1,'FAILED'),(2,'SUCCEEDED')])
def test_503_retry_counts_before_http_and_waits_for_actual_backoff(store,budget,expected):
    store.enqueue(request(),Limits(max_model_calls=budget));calls=[]
    def server(_):calls.append(1);return response(b'private provider error',status=503) if len(calls)==1 else response(answer())
    api=client(server);runner=worker(store,lambda ctx:done(ctx,api))
    assert runner.run_once('TENANT_A')['state']=='RETRY_WAIT'
    result=next_ready(runner)
    assert result['state']==expected and len(calls)==result['model_calls']==budget and result['retry_count']==1
    if budget==1:assert result['error_code']=='BUDGET_EXHAUSTED' and payload_count()==0


def test_cancelled_model_keeps_reservation_but_does_not_store_late_vector(store):
    row=store.enqueue(request());calls=[]
    def server(_):calls.append(1);store.cancel('TENANT_A',row['run_id']);return response(answer())
    result=worker(store,lambda ctx:done(ctx,client(server))).run_once('TENANT_A')
    assert result['state']=='CANCELLED' and result['model_calls']==1 and calls==[1] and payload_count()==0


def test_lost_atomic_model_commit_ack_does_not_repeat_the_embedding(store):
    store.enqueue(request());calls=[];api=client(lambda _:calls.append(1) or response(answer()))
    class LostAck:
        once=True
        def __getattr__(self,name):return getattr(store,name)
        def complete_payload_step(self,*args):
            value=store.complete_payload_step(*args)
            if self.once:self.once=False;raise psycopg.OperationalError('synthetic lost embedding commit ack')
            return value
    runner=worker(LostAck(),lambda ctx:done(ctx,api));assert runner.run_once('TENANT_A')['state']=='RETRY_WAIT'
    result=runner.run_once('TENANT_A')
    assert result['state']=='SUCCEEDED' and result['model_calls']==1 and calls==[1] and payload_count()==1


@pytest.mark.parametrize('fault',['vector','usage','profile'])
def test_hash_valid_but_semantically_corrupt_checkpoint_fails_without_another_request(store,fault):
    store.enqueue(request());calls=[];api=client(lambda _:calls.append(1) or response(answer()))
    def first(ctx):api.embed(ctx,'query-1',TEXT);raise TransientFailure('AFTER_EMBEDDING',delay_seconds=0)
    assert worker(store,first).run_once('TENANT_A')['state']=='RETRY_WAIT'
    with admin() as db:
        value=json.loads(db.execute('SELECT payload_json FROM agent_payload').fetchone()['payload_json'])
        if fault=='vector':value['vector']=[0.0]*1024
        elif fault=='usage':value['usage']['input_tokens']=True
        else:value['query_version']='changed-policy'
        raw,digest=encode('model-output-v1',value)
        db.execute('UPDATE agent_payload SET payload_json=%s,payload_hash=%s',(raw,digest))
    result=worker(store,lambda ctx:done(ctx,api)).run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']=='EMBEDDING_CHECKPOINT_INVALID' and calls==[1]


def test_missing_usage_remains_unknown_in_the_committed_checkpoint(store):
    store.enqueue(request());data=answer();data.pop('usage');api=client(lambda _:response(data))
    def handler(ctx):assert api.embed(ctx,'query-1',TEXT).input_tokens is None;return {'outcome':'done'}
    assert worker(store,handler).run_once('TENANT_A')['state']=='SUCCEEDED'
    with admin() as db:assert json.loads(db.execute('SELECT payload_json FROM agent_payload').fetchone()['payload_json'])['usage']=={'available':False}


@pytest.mark.parametrize('budget,expected',[(1,'FAILED'),(2,'SUCCEEDED')])
def test_actual_tcp_embedding_and_pg_worker_reservation_with_shared_chat_budget(store,monkeypatch,budget,expected):
    monkeypatch.setenv('HTTP_PROXY','http://127.0.0.1:1');monkeypatch.setenv('ALL_PROXY','http://127.0.0.1:1');monkeypatch.setenv('NO_PROXY','')
    store.enqueue(request(),Limits(max_model_calls=budget))
    with local_provider('normal',answer()) as (base,received):
        api=RuntimeEmbeddingClient(profile(base_url=base,timeout_seconds=2))
        def handler(ctx):
            api.embed(ctx,'query-1',TEXT)
            if budget==1:RuntimeChatClient(chat_profile(base_url=base)).complete(ctx,'plan-1',MESSAGES)
            return {'outcome':'done'}
        result=worker(store,handler).run_once('TENANT_A')
        assert result['state']==expected and result['model_calls']==1 and len(received)==1 and received[0][0]=='/v1/embeddings'
