"""Worker output recovery with real PG and actual child process death; model callbacks are synthetic."""
from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import subprocess
import sys
from threading import Event

import psycopg
import pytest

from ai.runtime.types import Lease,Limits
from ai.runtime.worker import RuntimeWorker,TransientFailure
from test_runtime_store_postgres import store,request,admin,expire

pytestmark=pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),reason='requires disposable runtime PostgreSQL')
INTENT={'operation':'plan','version':'prompt-v1'}
VALUE={'action':'reply','text':'synthetic durable model output'}


def worker(store,handler):return RuntimeWorker(store,{'runtime-v1':handler},'payload-worker')
def plan(ctx,callback):return ctx.payload_step('plan','MODEL','NONE',INTENT,'model-output-v1',callback)


def test_completed_value_is_loaded_as_fresh_data_without_reexecuting_or_recharging(store):
    row=store.enqueue(request());calls=[]
    def handler(ctx):
        value=plan(ctx,lambda:calls.append(1) or dict(VALUE))
        value['text']='mutated locally'
        assert plan(ctx,lambda:pytest.fail('model repeated'))==VALUE
        return {'outcome':'done'}
    result=worker(store,handler).run_once('TENANT_A')
    assert result['state']=='SUCCEEDED' and calls==[1]
    assert (result['step_count'],result['model_calls'])==(1,1)
    assert 'synthetic durable model output' not in str(store.steps('TENANT_A',row['run_id']))


def test_downstream_read_retry_reuses_completed_model_body_and_shared_budget(store):
    store.enqueue(request());calls=[];reads=[]
    def handler(ctx):
        assert plan(ctx,lambda:calls.append(1) or dict(VALUE))==VALUE
        def read():
            reads.append(1)
            if len(reads)==1:raise TransientFailure('READ_TIMEOUT',delay_seconds=0)
            return {'resource_ref':'record-1'}
        return ctx.step('lookup','TOOL','READ',{'operation':'lookup'},read)
    executor=worker(store,handler)
    assert executor.run_once('TENANT_A')['state']=='RETRY_WAIT'
    result=executor.run_once('TENANT_A')
    assert result['state']=='SUCCEEDED' and calls==[1] and reads==[1,1]
    assert (result['model_calls'],result['tool_calls'],result['retry_count'],result['step_count'])==(1,2,1,3)


@pytest.mark.parametrize('fault,expected',[
    ('DELETE FROM agent_payload','PAYLOAD_UNAVAILABLE'),
    ("UPDATE agent_payload SET payload_json='{}'",'PAYLOAD_INVALID'),
    ("UPDATE agent_payload SET payload_schema='tool-output-v1'",'PAYLOAD_SCHEMA_MISMATCH'),
    ("UPDATE agent_payload SET created_at=clock_timestamp()-interval '8 days',expires_at=clock_timestamp()-interval '1 day'",'PAYLOAD_UNAVAILABLE')])
def test_missing_corrupt_mistyped_and_expired_outputs_stop_without_new_model_calls(store,fault,expected):
    store.enqueue(request());calls=[]
    def handler(ctx):
        plan(ctx,lambda:calls.append(1) or dict(VALUE))
        raise TransientFailure('AFTER_MODEL',delay_seconds=0)
    executor=worker(store,handler);assert executor.run_once('TENANT_A')['state']=='RETRY_WAIT'
    with admin() as db:db.execute(fault)
    result=executor.run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']==expected and calls==[1]
    assert result['model_calls']==1 and result['retry_count']==1


class DelegatingStore:
    def __init__(self,actual):self.actual=actual
    def __getattr__(self,name):return getattr(self.actual,name)


def test_lost_payload_commit_ack_retries_control_flow_without_repeating_model(store):
    store.enqueue(request());calls=[]
    class LostAck(DelegatingStore):
        once=True
        def complete_payload_step(self,*args):
            result=self.actual.complete_payload_step(*args)
            if self.once:self.once=False;raise psycopg.OperationalError('synthetic-private-commit-detail')
            return result
    def handler(ctx):
        assert plan(ctx,lambda:calls.append(1) or dict(VALUE))==VALUE
        return {'outcome':'done'}
    executor=worker(LostAck(store),handler)
    first=executor.run_once('TENANT_A');assert first['state']=='RETRY_WAIT' and first['error_code']=='RUNTIME_PERSISTENCE_UNAVAILABLE'
    result=executor.run_once('TENANT_A')
    assert result['state']=='SUCCEEDED' and calls==[1]
    assert (result['attempt'],result['retry_count'],result['model_calls'],result['step_count'])==(2,1,1,1)
    assert 'synthetic-private' not in str(result)


@pytest.mark.parametrize('model_budget,expected,calls_expected',[(1,'FAILED',1),(2,'SUCCEEDED',2)])
def test_output_lost_before_commit_requires_a_new_reserved_call_with_original_budget(store,model_budget,expected,calls_expected):
    store.enqueue(request(),Limits(max_model_calls=model_budget));calls=[]
    class BeforeCommit(DelegatingStore):
        once=True
        def complete_payload_step(self,*args):
            if self.once:self.once=False;raise psycopg.OperationalError('synthetic storage unavailable')
            return self.actual.complete_payload_step(*args)
    def handler(ctx):plan(ctx,lambda:calls.append(1) or dict(VALUE));return {'outcome':'done'}
    executor=worker(BeforeCommit(store),handler)
    assert executor.run_once('TENANT_A')['state']=='RETRY_WAIT'
    result=executor.run_once('TENANT_A')
    assert result['state']==expected and len(calls)==calls_expected
    assert result['model_calls']==calls_expected and result['retry_count']==1
    if expected=='FAILED':assert result['error_code']=='BUDGET_EXHAUSTED'


def test_db_unavailable_during_transition_does_not_invent_a_retry_or_success(store):
    row=store.enqueue(request());calls=[]
    class Offline(DelegatingStore):
        offline=False
        def complete_payload_step(self,*args):
            self.actual.complete_payload_step(*args);self.offline=True
            raise psycopg.OperationalError('synthetic database offline')
        def heartbeat(self,*args,**kwargs):
            if self.offline:raise psycopg.OperationalError('synthetic database offline')
            return self.actual.heartbeat(*args,**kwargs)
    def handler(ctx):plan(ctx,lambda:calls.append(1) or dict(VALUE));return {'outcome':'done'}
    with pytest.raises(psycopg.OperationalError):worker(Offline(store),handler).run_once('TENANT_A')
    current=store.get('TENANT_A',row['run_id'])
    assert current['state']=='RUNNING' and current['retry_count']==0
    expire(Lease.from_run(current))
    result=worker(store,handler).run_once('TENANT_A')
    assert result['state']=='SUCCEEDED' and calls==[1] and result['attempt']==2 and result['model_calls']==1


def test_cancellation_during_model_callback_never_commits_late_output(store):
    row=store.enqueue(request());entered,release=Event(),Event()
    def handler(ctx):
        def model():entered.set();assert release.wait(10);return dict(VALUE)
        plan(ctx,model);return {'outcome':'done'}
    with ThreadPoolExecutor(1) as pool:
        future=pool.submit(worker(store,handler).run_once,'TENANT_A')
        try:assert entered.wait(5);store.cancel('TENANT_A',row['run_id'])
        finally:release.set()
        assert future.result(timeout=10)['state']=='CANCELLED'
    with admin() as db:assert db.execute('SELECT count(*) AS n FROM agent_payload').fetchone()['n']==0


def test_another_logical_steps_payload_reference_is_not_a_valid_checkpoint(store):
    store.enqueue(request())
    def handler(ctx):
        plan(ctx,lambda:dict(VALUE))
        ref=ctx.store.steps(ctx.lease.tenant,ctx.lease.run_id)[0]['result_ref']
        ctx.step('other','MODEL','NONE',INTENT,lambda:ref)
        ctx.payload_step('other','MODEL','NONE',INTENT,'model-output-v1',lambda:pytest.fail('model repeated'))
        return {'outcome':'must-not-run'}
    result=worker(store,handler).run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']=='PAYLOAD_REFERENCE_INVALID'


def test_invalid_output_is_permanent_and_write_payload_schema_never_executes(store):
    row=store.enqueue(request())
    result=worker(store,lambda ctx:plan(ctx,lambda:{'score':float('nan')})).run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']=='PAYLOAD_FORMAT_INVALID' and result['retry_count']==0
    assert store.steps('TENANT_A',row['run_id'])[0]['state']=='UNKNOWN'
    store.enqueue(request(session='SECOND'))
    result=worker(store,lambda ctx:ctx.payload_step('write','TOOL','WRITE',{},'tool-output-v1',lambda:pytest.fail('write executed'))).run_once('TENANT_A')
    assert result['state']=='FAILED' and result['step_count']==0 and result['tool_calls']==0


def test_real_process_death_after_payload_commit_reuses_output_under_new_owner(store):
    row=store.enqueue(request());code='''
import os
import psycopg
from psycopg.rows import dict_row
from ai.runtime.store import RuntimeStore
from ai.runtime.worker import RuntimeWorker
store=RuntimeStore(lambda:psycopg.connect(os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'],row_factory=dict_row))
def handler(ctx):
    ctx.payload_step('plan','MODEL','NONE',{'operation':'plan','version':'prompt-v1'},'model-output-v1',lambda:{'action':'reply','text':'synthetic durable model output'})
    print('PAYLOAD_COMMITTED',flush=True)
    os._exit(23)
RuntimeWorker(store,{'runtime-v1':handler},'crashed-payload-worker').run_once('TENANT_A')
'''
    child={key:value for key,value in os.environ.items() if not key.startswith('SUPPORTFLOW_')}
    child.update(SUPPORTFLOW_RUNTIME_TEST_DSN=os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'],PYTHONUTF8='1',PYTHONPATH=str(Path(__file__).resolve().parents[1]))
    completed=subprocess.run([sys.executable,'-c',code],env=child,capture_output=True,text=True,timeout=30)
    assert completed.returncode==23 and 'PAYLOAD_COMMITTED' in completed.stdout
    old=store.get('TENANT_A',row['run_id']);assert old['state']=='RUNNING'
    expire(Lease.from_run(old))
    def recovered(ctx):
        assert plan(ctx,lambda:pytest.fail('successful model was reexecuted'))==VALUE
        return {'outcome':'recovered'}
    result=worker(store,recovered).run_once('TENANT_A')
    assert result['state']=='SUCCEEDED'
    assert (result['attempt'],result['retry_count'],result['model_calls'],result['step_count'])==(2,1,1,1)
