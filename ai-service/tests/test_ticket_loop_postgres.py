"""Actual Run/Step/checkpoint/budgets; model and business HTTP are protocol Stubs."""
import json
import os

import psycopg
import pytest

from ai.runtime.payloads import encode
from ai.runtime.types import Limits
from ai.runtime.worker import RuntimeWorker
from test_runtime_store_postgres import store,request,admin
from test_ticket_loop import fixture,QUERY,REPORT


pytestmark=pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),reason='requires disposable runtime PostgreSQL')


def prepare(store,actions=None,limits=None,**kwargs):
    source=request();row=store.enqueue(source,limits or Limits())
    loop,_,http,models,reports,state=fixture(actions or [QUERY,REPORT],source_input=source,run_id=row['run_id'],**kwargs)
    return row,loop,http,models,state


def runner(store,loop):return RuntimeWorker(store,{'runtime-v1':loop},'ticket-loop-worker')
def artifacts():
    with admin() as db:
        return [json.loads(row['payload_json']) for row in db.execute("SELECT payload_json FROM agent_payload WHERE payload_schema='working-state-v1'").fetchall()]


def test_dynamic_read_loop_finishes_with_durable_verified_report_and_shared_counts(store):
    row,loop,http,models,state=prepare(store)
    result=runner(store,loop).run_once('TENANT_A')
    assert result['state']=='SUCCEEDED' and result['result_ref']['outcome']=='ticket-status-ready'
    assert len(models)==2 and (result['model_calls'],result['tool_calls'],result['step_count'],result['retry_count'])==(2,5,10,0)
    report,=artifacts();assert report['status']==2 and report['text']=='工单 TK-1 当前状态：处理中。'
    assert not any(request.url.path.endswith('/reply') for request in http)
    with admin() as db:assert db.execute('SELECT count(*) AS n FROM agent_payload').fetchone()['n']==5


def test_clarification_finishes_as_an_explicit_artifact_without_a_ticket_query(store):
    row,loop,http,models,state=prepare(store,[{'action':'clarify'}])
    result=runner(store,loop).run_once('TENANT_A')
    assert result['state']=='SUCCEEDED' and result['result_ref']['outcome']=='clarification-ready'
    assert (result['model_calls'],result['tool_calls'],result['step_count'])==(1,2,5)
    report,=artifacts();assert report['kind']=='clarification' and report['text']=='请提供需要查询的工单编号。'
    assert not any('/tickets/' in request.url.path for request in http)


def test_status_changed_during_final_planning_is_reauthorized_and_reported_from_new_business_value(store):
    def change(turn,state):
        if turn==2:state['status']=4
    row,loop,http,models,state=prepare(store,after_model=change)
    result=runner(store,loop).run_once('TENANT_A')
    assert result['state']=='SUCCEEDED' and (result['model_calls'],result['tool_calls'],result['step_count'])==(2,6,11)
    report,=artifacts();assert report['status']==4 and '已解决' in report['text']
    assert json.loads(models[1]['messages'][-1]['content'])['value']['status']==2


def test_revoked_owner_after_model_report_decision_does_not_turn_old_fact_into_success(store):
    def revoke(turn,state):
        if turn==2:state['owner']=False
    row,loop,http,models,state=prepare(store,after_model=revoke)
    result=runner(store,loop).run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']=='TICKET_REVOKED' and artifacts()==[]


@pytest.mark.parametrize('proposal,code',[
    ({'action':'report','evidence':'tool-99'},'REPORT_EVIDENCE_INVALID'),
    ({'action':'tool','call':{'name':'shell','version':'v1','arguments':{'cmd':'arbitrary-code'}}},'TOOL_UNKNOWN'),
    ({'action':'tool','call':{'name':'get_ticket_status','version':'ticket-status-v2','arguments':{'ticket_no':'TK-1','tenant':'TENANT_B'}}},'TOOL_ARGUMENTS_INVALID'),
    ({'action':'report','evidence':'tool-0','text':'已退款'},'PLANNER_DECISION_INVALID'),
])
def test_invalid_model_actions_have_no_ticket_call_or_verified_artifact(store,proposal,code):
    row,loop,http,models,state=prepare(store,[proposal])
    result=runner(store,loop).run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']==code and artifacts()==[]
    assert result['model_calls']==1 and result['tool_calls']==2 and len(models)==1
    assert not any('/tickets/' in request.url.path for request in http)


def test_retry_after_model_503_replays_source_context_and_uses_original_budgets(store):
    row,loop,http,models,state=prepare(store,model_faults={1:503});worker=runner(store,loop)
    assert worker.run_once('TENANT_A')['state']=='RETRY_WAIT'
    with admin() as db:db.execute("UPDATE agent_run SET ready_at=clock_timestamp() WHERE state='RETRY_WAIT'")
    result=worker.run_once('TENANT_A')
    assert result['state']=='SUCCEEDED' and len(models)==3 and len(artifacts())==1
    assert (result['model_calls'],result['tool_calls'],result['step_count'],result['retry_count'])==(3,6,12,1)


def test_insufficient_model_budget_does_not_make_a_free_final_planner_call(store):
    row,loop,http,models,state=prepare(store,limits=Limits(max_model_calls=1))
    result=runner(store,loop).run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']=='BUDGET_EXHAUSTED' and len(models)==1 and artifacts()==[]
    assert result['model_calls']==1 and result['tool_calls']==4


def test_final_authorization_is_not_skipped_even_when_only_cached_ticket_data_is_needed(store):
    row,loop,http,models,state=prepare(store,limits=Limits(max_tool_calls=4))
    result=runner(store,loop).run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']=='BUDGET_EXHAUSTED' and artifacts()==[]
    assert len(models)==2 and result['tool_calls']==4 and len(http)==4


def test_turn_limit_stops_without_a_silent_additional_model_attempt(store):
    row,loop,http,models,state=prepare(store,[QUERY],max_turns=1)
    result=runner(store,loop).run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']=='PLANNER_TURN_LIMIT' and len(models)==1 and artifacts()==[]


def test_lost_model_checkpoint_commit_ack_resumes_loop_without_repeating_completed_planning(store):
    row,loop,http,models,state=prepare(store)
    class LostAck:
        once=True
        def __getattr__(self,name):return getattr(store,name)
        def complete_payload_step(self,*args):
            value=store.complete_payload_step(*args)
            if self.once and args[2]=='model-output-v1':self.once=False;raise psycopg.OperationalError('synthetic lost planning acknowledgement')
            return value
    worker=runner(LostAck(),loop);assert worker.run_once('TENANT_A')['state']=='RETRY_WAIT'
    result=worker.run_once('TENANT_A')
    assert result['state']=='SUCCEEDED' and len(models)==2 and len(artifacts())==1
    assert (result['model_calls'],result['tool_calls'],result['step_count'],result['retry_count'])==(2,6,11,1)


def test_cancellation_after_provider_response_does_not_publish_a_late_plan_or_report(store):
    source=request();row=store.enqueue(source)
    def cancel(turn,state):
        if turn==1:store.cancel(source.tenant,row['run_id'])
    loop,_,http,models,reports,state=fixture([QUERY],source_input=source,run_id=row['run_id'],after_model=cancel)
    result=runner(store,loop).run_once('TENANT_A')
    assert result['state']=='CANCELLED' and result['model_calls']==1 and artifacts()==[]
    with admin() as db:assert db.execute("SELECT count(*) AS n FROM agent_payload WHERE payload_schema='model-output-v1'").fetchone()['n']==0


def test_hash_valid_boolean_status_cannot_masquerade_as_numeric_report_after_resume(store):
    row,loop,http,models,state=prepare(store,limits=Limits(max_steps=16,max_tool_calls=12));state['status']=1
    class LostReportAck:
        once=True
        def __getattr__(self,name):return getattr(store,name)
        def complete_payload_step(self,*args):
            value=store.complete_payload_step(*args)
            if self.once and args[2]=='working-state-v1':self.once=False;raise psycopg.OperationalError('synthetic lost report ack')
            return value
    worker=runner(LostReportAck(),loop);assert worker.run_once('TENANT_A')['state']=='RETRY_WAIT'
    with admin() as db:
        data=json.loads(db.execute("SELECT payload_json FROM agent_payload WHERE payload_schema='working-state-v1'").fetchone()['payload_json'])
        data['status']=True;raw,digest=encode('working-state-v1',data)
        db.execute("UPDATE agent_payload SET payload_json=%s,payload_hash=%s WHERE payload_schema='working-state-v1'",(raw,digest))
    result=worker.run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']=='REPORT_CHECKPOINT_INVALID' and len(models)==2
