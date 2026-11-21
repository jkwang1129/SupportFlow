import asyncio
import copy
from dataclasses import replace
import json
import time

import httpx
import pytest

from ai.core.chat_transport import ModelCallFailure,_send_model_json
from ai.runtime.embeddings import EmbeddingProfile,RuntimeEmbeddingClient,DIMENSIONS
from ai.runtime.worker import PermanentFailure,TransientFailure
from test_runtime_customer_tools import source_context
from test_chat_transport import response,local_provider

TEXT='synthetic private query'


def profile(**kwargs):
    return EmbeddingProfile(**(dict(tenant='TENANT_A',profile_ref='query-embedding-v1',provider='fixture',model='fixture-embedding',
        base_url='http://model.test/v1',api_key='synthetic-embedding-private-credential') | kwargs))


def answer():
    return dict(object='list',model='fixture-embedding',data=[dict(object='embedding',index=0,embedding=[.25]+[0.0]*(DIMENSIONS-1))],
                usage=dict(prompt_tokens=8,total_tokens=8))


def client(server,model_profile=None):return RuntimeEmbeddingClient(model_profile or profile(),transport=httpx.MockTransport(server))


def test_single_embedding_uses_fixed_route_model_dimension_and_one_model_reservation():
    calls=[];ctx=source_context()
    value=client(lambda req:calls.append(req) or response(answer())).embed(ctx,'query-1',TEXT)
    assert len(calls)==1 and calls[0].url.path=='/v1/embeddings' and value.input_tokens==8
    assert json.loads(calls[0].content)==dict(model='fixture-embedding',input=[TEXT],dimensions=1024,encoding_format='float')
    assert len(value.vector)==1024 and value.vector[0]==.25
    _,kind,effect,intent=ctx.steps[0]
    assert (kind,effect)==('MODEL','NONE') and intent['operation']=='model.embedding' and intent['arguments_sha256']==value.request_hash
    assert TEXT not in str(intent) and profile().api_key not in repr(profile())+repr(value)+str(intent)
    assert '0.25' not in repr(value)
    with pytest.raises(TypeError):value.vector[0]=1


def test_profile_rotation_retains_policy_but_other_strategy_changes_do_not():
    base=profile()
    assert replace(base,api_key='rotated-embedding-private-credential').definition_hash()==base.definition_hash()
    for item in (replace(base,model='other'),replace(base,tenant='TENANT_B'),replace(base,query_version='query-v2'),
                 replace(base,base_url='http://other.test/v1'),replace(base,timeout_seconds=5)):
        assert item.definition_hash()!=base.definition_hash()


@pytest.mark.parametrize('field,value',[('tenant','PLATFORM'),('profile_ref','../query'),('model','bad/model'),
    ('api_key','short'),('api_key','synthetic-secret\r\n'),('timeout_seconds',True),('timeout_seconds',31),
    ('base_url','http://user:pass@model.test/v1'),('base_url','http://model.test/v1?key=private')])
def test_bad_profiles_fail_without_worker_or_http(field,value):
    with pytest.raises(ValueError):profile(**{field:value})


@pytest.mark.parametrize('text',[None,{},'', ' ','\0','\ud800','x'*4097,'中'*1366,profile().api_key])
def test_bad_query_is_rejected_before_any_model_reservation(text):
    ctx=source_context()
    with pytest.raises(PermanentFailure,match='EMBEDDING_INPUT_INVALID'):
        client(lambda _:pytest.fail('bad query reached HTTP')).embed(ctx,'query-1',text)
    assert ctx.steps==[]


def test_other_tenant_profile_cannot_use_the_current_run():
    ctx=source_context()
    with pytest.raises(PermanentFailure,match='MODEL_PROFILE_DENIED'):
        client(lambda _:pytest.fail('other tenant reached HTTP'),profile(tenant='TENANT_B')).embed(ctx,'query-1',TEXT)
    assert ctx.steps==[]


@pytest.mark.parametrize('fault',['empty','short','long','boolean','string','zero','tiny','huge','nan','inf',
    'two','bad_index','boolean_index','bad_object','bad_model','bad_usage','wrong_total'])
def test_malformed_embeddings_and_usage_never_become_successful_results(fault):
    data=answer();item=data['data'][0]
    if fault=='empty':data['data']=[]
    elif fault=='short':item['embedding']=[.25]*1023
    elif fault=='long':item['embedding']=[.25]*1025
    elif fault in ('boolean','string','huge','nan','inf'):
        item['embedding'][0]={'boolean':True,'string':'.25','huge':1e7,'nan':float('nan'),'inf':float('inf')}[fault]
    elif fault in ('zero','tiny'):item['embedding']=[0.0 if fault=='zero' else 1e-12]*1024
    elif fault=='two':data['data'].append(copy.deepcopy(item))
    elif fault=='bad_index':item['index']=1
    elif fault=='boolean_index':item['index']=False
    elif fault=='bad_object':item['object']='text'
    elif fault=='bad_model':data['model']='another-model'
    elif fault=='bad_usage':data['usage']['prompt_tokens']=True
    elif fault=='wrong_total':data['usage']['total_tokens']=9
    calls=[]
    with pytest.raises(PermanentFailure,match='^(EMBEDDING_OUTPUT_INVALID|MODEL_RESPONSE_INVALID)$'):
        client(lambda _:calls.append(1) or response(data)).embed(source_context(),'query-1',TEXT)
    assert calls==[1]


@pytest.mark.parametrize('usage',[None,{},dict(prompt_tokens=8)])
def test_missing_usage_is_unknown_rather_than_zero(usage):
    data=answer();data['usage']=usage
    assert client(lambda _:response(data)).embed(source_context(),'query-1',TEXT).input_tokens is None


@pytest.mark.parametrize('status,code,retryable',[(429,'MODEL_RATE_LIMITED',True),(503,'MODEL_UNAVAILABLE',True),
    (401,'MODEL_AUTHORIZATION_DENIED',False),(302,'MODEL_PROTOCOL_ERROR',False)])
def test_provider_errors_are_stable_and_no_fallback_or_hidden_second_request(status,code,retryable):
    calls=[]
    with pytest.raises(TransientFailure if retryable else PermanentFailure,match='^'+code+'$') as failure:
        client(lambda _:calls.append(1) or response(b'private provider echo',status=status)).embed(source_context(),'query-1',TEXT)
    assert str(failure.value)==code and calls==[1]


def test_a_cached_checkpoint_is_revalidated_and_cannot_change_dimensions_or_usage():
    ctx=source_context();saved=[]
    def persist(key,kind,effect,intent,schema,execute):
        if not saved:saved.append(execute())
        return copy.deepcopy(saved[0])
    ctx.payload_step=persist;calls=[];api=client(lambda _:calls.append(1) or response(answer()))
    first=api.embed(ctx,'query-1',TEXT)
    assert api.embed(ctx,'query-1',TEXT)==first and calls==[1]
    saved[0]['usage']['input_tokens']=True
    with pytest.raises(PermanentFailure,match='EMBEDDING_CHECKPOINT_INVALID'):api.embed(ctx,'query-1',TEXT)
    assert calls==[1]


def test_common_transport_cannot_be_repurposed_as_an_arbitrary_model_endpoint():
    with pytest.raises(ModelCallFailure,match='MODEL_OPERATION_INVALID') as failure:
        asyncio.run(_send_model_json(profile().chosen(),dict(model='fixture-embedding'),1,endpoint='https://untrusted.test',
            transport=httpx.MockTransport(lambda _:pytest.fail('unknown operation reached HTTP'))))
    assert failure.value.attempted is False


@pytest.mark.parametrize('mode,code',[('normal',None),('redirect','MODEL_PROTOCOL_ERROR'),('slow','MODEL_TIMEOUT')])
def test_real_tcp_embedding_route_deadline_and_redirect_without_environment_proxy(monkeypatch,mode,code):
    monkeypatch.setenv('HTTP_PROXY','http://127.0.0.1:1');monkeypatch.setenv('ALL_PROXY','http://127.0.0.1:1');monkeypatch.setenv('NO_PROXY','')
    with local_provider(mode,answer()) as (base,received):
        api=RuntimeEmbeddingClient(profile(base_url=base,timeout_seconds=1 if mode=='slow' else 2))
        if code:
            with pytest.raises(TransientFailure if mode=='slow' else PermanentFailure,match='^'+code+'$'):
                api.embed(source_context(),'query-1',TEXT)
        else:assert api.embed(source_context(),'query-1',TEXT).vector[0]==.25
        assert len(received)==1 and received[0][0]=='/v1/embeddings'


@pytest.mark.parametrize('expiry',[True,'later',float('nan'),float('inf')])
def test_invalid_host_deadline_is_rejected_without_model_reservation(expiry):
    ctx=source_context()
    with pytest.raises(ValueError):client(lambda _:pytest.fail('invalid expiry reached HTTP')).embed(ctx,'query-1',TEXT,expires_at=expiry)
    assert ctx.steps==[]


def test_expired_host_deadline_does_not_reserve_or_send_embedding():
    ctx=source_context()
    with pytest.raises(TransientFailure,match='^MODEL_TIMEOUT$'):
        client(lambda _:pytest.fail('expired request reached HTTP')).embed(ctx,'query-1',TEXT,expires_at=time.monotonic()-1)
    assert ctx.steps==[]


def test_deadline_expiring_during_model_reservation_stops_before_http(monkeypatch):
    from ai.runtime import embeddings
    clock=[10.0];monkeypatch.setattr(embeddings,'monotonic',lambda:clock[0])
    ctx=source_context();original=ctx.payload_step
    def reserve(key,kind,effect,intent,schema,execute):
        clock[0]=12.0
        return original(key,kind,effect,intent,schema,execute)
    ctx.payload_step=reserve
    with pytest.raises(TransientFailure,match='^MODEL_TIMEOUT$'):
        client(lambda _:pytest.fail('expired reservation reached HTTP')).embed(ctx,'query-1',TEXT,expires_at=11.0)
    assert len(ctx.steps)==1 and ctx.steps[0][1]=='MODEL'


@pytest.mark.parametrize('remaining,expected',[(1,1),(30,10)])
def test_per_attempt_host_deadline_only_reduces_network_time_and_not_profile_hash(monkeypatch,remaining,expected):
    from ai.runtime import embeddings
    seen=[];monkeypatch.setattr(embeddings,'monotonic',lambda:10.0)
    async def send(chosen,payload,timeout,**kwargs):seen.append(timeout);return answer()
    monkeypatch.setattr(embeddings,'_send_embedding_json',send)
    api=client(lambda _:pytest.fail('patched sender not used'));before=api.profile.definition_hash()
    api.embed(source_context(),'query-1',TEXT,expires_at=10.0+remaining)
    assert seen==[expected] and api.profile.definition_hash()==before


def test_actual_tcp_embedding_is_bounded_by_host_deadline_below_profile_limit():
    with local_provider('slow',answer()) as (base,received):
        api=RuntimeEmbeddingClient(profile(base_url=base,timeout_seconds=3))
        with pytest.raises(TransientFailure,match='^MODEL_TIMEOUT$'):
            api.embed(source_context(),'query-1',TEXT,expires_at=time.monotonic()+1)
        assert len(received)==1
