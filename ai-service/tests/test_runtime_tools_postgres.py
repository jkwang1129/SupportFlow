"""Read-tool executor with actual Run/Step budgets, RLS and persisted results.

Resource ACL/remote results here are deterministic adapters; actual business HTTP
qualification is a separate delivery batch, not inferred from these tests.
"""
from concurrent.futures import ThreadPoolExecutor
import os
from threading import Event

import pytest

from ai.runtime.tools import ToolCall, ToolExecutor, ToolRegistry, ToolUnavailable, ReadAuthorization
from ai.runtime.types import Limits, fingerprint
from ai.runtime.worker import RuntimeWorker, TransientFailure
from test_runtime_store_postgres import store, request, admin
from test_runtime_tools import ANSWER, definition


pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'), reason='requires disposable runtime PostgreSQL')


def proposal(ticket='T-1'):
    return ToolCall('get_ticket_status', 'ticket-v1', {'ticket_no': ticket})


def executor(**kwargs):
    return ToolExecutor(ToolRegistry([definition(**kwargs)]), frozenset({'ticket:read'}))


def worker(store, handler):
    return RuntimeWorker(store, {'runtime-v1': handler}, 'tool-worker')


def payload_count():
    with admin() as db:
        return db.execute('SELECT count(*) AS n FROM agent_payload').fetchone()['n']


def test_tool_success_persists_only_verified_body_with_two_accounted_reads(store):
    row = store.enqueue(request())
    tool = executor()
    def handler(ctx):
        assert tool.execute(ctx, 'call-1', proposal()) == ANSWER
        return {'outcome': 'done'}
    result = worker(store, handler).run_once('TENANT_A')
    assert result['state'] == 'SUCCEEDED' and result['tool_calls'] == 2 and result['model_calls'] == 0
    steps = store.steps('TENANT_A', row['run_id'])
    assert len(steps) == 2 and all(s['state'] == 'SUCCEEDED' for s in steps)
    assert 'synthetic issue' not in str(steps) and payload_count() == 1


@pytest.mark.parametrize('revoked', [False, True])
def test_resume_reauthorizes_before_reusing_previous_tool_body(store, revoked):
    store.enqueue(request())
    checks, calls = [], []
    def authorize(identity, args, deadline):
        checks.append(identity.source.principal_ref)
        return not revoked or len(checks) == 1
    tool = executor(authorize=authorize, invoke=lambda *_: calls.append(1) or dict(ANSWER))
    attempts = []
    def handler(ctx):
        value = tool.execute(ctx, 'call-1', proposal())
        attempts.append(1)
        assert value == ANSWER
        if len(attempts) == 1:
            raise TransientFailure('AFTER_READ', delay_seconds=0)
        return {'outcome': 'done'}
    runner = worker(store, handler)
    assert runner.run_once('TENANT_A')['state'] == 'RETRY_WAIT'
    result = runner.run_once('TENANT_A')
    assert checks == ['visitor:42', 'visitor:42'] and calls == [1]
    assert result['tool_calls'] == 3 and result['step_count'] == 3 and result['retry_count'] == 1
    if revoked:
        assert result['state'] == 'FAILED' and result['error_code'] == 'TOOL_RESOURCE_DENIED' and attempts == [1]
    else:
        assert result['state'] == 'SUCCEEDED' and attempts == [1, 1]


@pytest.mark.parametrize('max_tools', [1, 2])
def test_authorization_and_cached_reuse_cannot_bypass_the_shared_tool_budget(store, max_tools):
    store.enqueue(request(), Limits(max_tool_calls=max_tools))
    calls = []
    tool = executor(invoke=lambda *_: calls.append(1) or dict(ANSWER))
    def handler(ctx):
        tool.execute(ctx, 'call-1', proposal())
        raise TransientFailure('AFTER_READ', delay_seconds=0)
    runner = worker(store, handler)
    first = runner.run_once('TENANT_A')
    result = runner.run_once('TENANT_A') if first['state'] == 'RETRY_WAIT' else first
    assert result['state'] == 'FAILED' and result['error_code'] == 'BUDGET_EXHAUSTED'
    assert result['tool_calls'] == max_tools and calls == ([1] if max_tools == 2 else [])


def test_classified_timeout_uses_new_steps_and_retains_original_budget(store):
    store.enqueue(request())
    calls = []
    def invoke(*_):
        calls.append(1)
        if len(calls) == 1:
            raise TimeoutError('private provider details')
        return dict(ANSWER)
    tool = executor(invoke=invoke)
    runner = worker(store, lambda ctx: (tool.execute(ctx, 'call-1', proposal()), {'outcome': 'done'})[1])
    assert runner.run_once('TENANT_A')['state'] == 'RETRY_WAIT'
    # Explicitly advance only the synthetic retry timer; no deadline/budget reset.
    with admin() as db:
        db.execute("UPDATE agent_run SET ready_at=clock_timestamp() WHERE state='RETRY_WAIT'")
    result = runner.run_once('TENANT_A')
    assert result['state'] == 'SUCCEEDED' and calls == [1, 1]
    assert (result['retry_count'], result['tool_calls'], result['step_count']) == (1, 4, 4)
    assert payload_count() == 1


def test_invalid_output_is_permanent_and_never_persisted_as_a_successful_body(store):
    row = store.enqueue(request())
    tool = executor(invoke=lambda *_: ANSWER | {'secret': 'private-field'})
    result = worker(store, lambda ctx: tool.execute(ctx, 'call-1', proposal())).run_once('TENANT_A')
    assert result['state'] == 'FAILED' and result['error_code'] == 'TOOL_RESULT_INVALID'
    assert result['retry_count'] == 0 and payload_count() == 0
    steps = store.steps('TENANT_A', row['run_id'])
    assert steps[1]['state'] == 'UNKNOWN' and 'private-field' not in str(steps) + str(result)


def test_resource_ownership_denial_prevents_actual_invocation(store):
    store.enqueue(request())
    def authorize(identity, args, deadline):
        return identity.source.tenant == 'TENANT_A' and identity.source.principal_ref == 'visitor:42' and args['ticket_no'] == 'T-1'
    tool = executor(authorize=authorize, invoke=lambda *_: pytest.fail('foreign resource invoked'))
    result = worker(store, lambda ctx: tool.execute(ctx, 'call-1', proposal('OTHER-CUSTOMER-TICKET'))).run_once('TENANT_A')
    assert result['state'] == 'FAILED' and result['error_code'] == 'TOOL_RESOURCE_DENIED'
    assert result['tool_calls'] == 1 and payload_count() == 0


def test_cancel_during_authorization_does_not_expose_or_execute_a_tool_result(store):
    row = store.enqueue(request())
    entered, release = Event(), Event()
    def authorize(*_):
        entered.set()
        assert release.wait(10)
        return True
    tool = executor(authorize=authorize, invoke=lambda *_: pytest.fail('cancelled tool invoked'))
    runner = worker(store, lambda ctx: tool.execute(ctx, 'call-1', proposal()))
    with ThreadPoolExecutor(1) as pool:
        future = pool.submit(runner.run_once, 'TENANT_A')
        try:
            assert entered.wait(5)
            store.cancel('TENANT_A', row['run_id'])
        finally:
            release.set()
        assert future.result(timeout=10)['state'] == 'CANCELLED'
    assert payload_count() == 0


@pytest.mark.parametrize('fault', [{'status': 'FAKE_SUCCESS'}, {'ticket_no': 'OTHER-CUSTOMER'}])
def test_schema_and_resource_binding_are_checked_again_on_integrity_valid_cached_data(store, fault):
    store.enqueue(request())
    calls = []
    tool = executor(invoke=lambda *_: calls.append(1) or dict(ANSWER))
    def handler(ctx):
        tool.execute(ctx, 'call-1', proposal())
        raise TransientFailure('AFTER_READ', delay_seconds=0)
    runner = worker(store, handler)
    assert runner.run_once('TENANT_A')['state'] == 'RETRY_WAIT'
    from ai.runtime.payloads import encode
    raw, digest = encode('tool-output-v1', ANSWER | fault)
    with admin() as db:
        db.execute('UPDATE agent_payload SET payload_json=%s,payload_hash=%s', (raw, digest))
    result = runner.run_once('TENANT_A')
    assert result['state'] == 'FAILED' and result['error_code'] == 'TOOL_RESULT_INVALID' and calls == [1]


def test_parameter_change_on_resume_cannot_substitute_the_cached_result(store):
    store.enqueue(request())
    calls, attempts = [], []
    tool = executor(invoke=lambda *_: calls.append(1) or dict(ANSWER))
    def handler(ctx):
        attempts.append(1)
        tool.execute(ctx, 'call-1', proposal('T-1' if len(attempts) == 1 else 'T-2'))
        raise TransientFailure('AFTER_READ', delay_seconds=0)
    runner = worker(store, handler)
    assert runner.run_once('TENANT_A')['state'] == 'RETRY_WAIT'
    result = runner.run_once('TENANT_A')
    assert result['state'] == 'FAILED' and result['error_code'] == 'EXECUTION_ERROR' and calls == [1]


def test_transient_reads_stop_at_existing_run_retry_budget(store):
    store.enqueue(request())
    calls = []
    def invoke(*_):
        calls.append(1)
        raise ToolUnavailable('TOOL_RATE_LIMITED')
    tool = executor(invoke=invoke)
    runner = worker(store, lambda ctx: tool.execute(ctx, 'call-1', proposal()))
    for _ in range(3):
        result = runner.run_once('TENANT_A')
        with admin() as db:
            db.execute("UPDATE agent_run SET ready_at=clock_timestamp() WHERE state='RETRY_WAIT'")
    assert result['state'] == 'FAILED' and result['error_code'] == 'RETRY_EXHAUSTED'
    assert result['retry_count'] == 2 and result['tool_calls'] == 6 and calls == [1, 1, 1]


def versioned(current, checks, calls):
    def authorize(identity, args, deadline):
        checks.append(1)
        value=ANSWER | {'status':current['status']}
        return ReadAuthorization({'resource_ref':args['ticket_no'],'version':'ticket-v1','arguments_sha256':fingerprint(value)})
    def invoke(identity,args,deadline):
        calls.append(current['status'])
        return ANSWER | {'status':current['status']}
    def verify(identity,args,result,grant):
        return result['ticket_no']==args['ticket_no'] and fingerprint(result)==grant.references['arguments_sha256']
    return executor(authorize=authorize,invoke=invoke,verify=verify,cache_policy='current-version')


@pytest.mark.parametrize('changed', [False, True])
def test_current_version_refreshes_only_when_proof_changes_with_original_counts(store,changed):
    store.enqueue(request());current={'status':'OPEN'};checks=[];calls=[];returned=[]
    tool=versioned(current,checks,calls)
    def handler(ctx):
        returned.append(tool.execute(ctx,'call-1',proposal())['status'])
        if len(returned)==1:raise TransientFailure('AFTER_READ',delay_seconds=0)
        return {'outcome':'done'}
    runner=worker(store,handler);assert runner.run_once('TENANT_A')['state']=='RETRY_WAIT'
    if changed:current['status']='CLOSED'
    result=runner.run_once('TENANT_A')
    assert result['state']=='SUCCEEDED' and result['retry_count']==1 and result['model_calls']==0
    assert checks==[1,1] and returned==(['OPEN','CLOSED'] if changed else ['OPEN','OPEN'])
    assert calls==(['OPEN','CLOSED'] if changed else ['OPEN'])
    assert (result['tool_calls'],result['step_count'],payload_count())==((4,5,2) if changed else (3,4,1))


def test_refresh_requires_a_new_tool_reservation_and_does_not_bypass_limit(store):
    store.enqueue(request(),Limits(max_tool_calls=3));current={'status':'OPEN'};checks=[];calls=[]
    tool=versioned(current,checks,calls)
    def handler(ctx):
        tool.execute(ctx,'call-1',proposal());raise TransientFailure('AFTER_READ',delay_seconds=0)
    runner=worker(store,handler);assert runner.run_once('TENANT_A')['state']=='RETRY_WAIT'
    current['status']='CLOSED';result=runner.run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']=='BUDGET_EXHAUSTED'
    assert result['tool_calls']==3 and result['step_count']==4 and calls==['OPEN'] and checks==[1,1] and payload_count()==1


def test_logical_anchor_blocks_argument_substitution_before_any_new_authorization(store):
    store.enqueue(request());current={'status':'OPEN'};checks=[];calls=[];attempts=[]
    tool=versioned(current,checks,calls)
    def handler(ctx):
        attempts.append(1)
        tool.execute(ctx,'call-1',proposal('T-1' if len(attempts)==1 else 'T-2'))
        raise TransientFailure('AFTER_READ',delay_seconds=0)
    runner=worker(store,handler);assert runner.run_once('TENANT_A')['state']=='RETRY_WAIT'
    result=runner.run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']=='EXECUTION_ERROR'
    assert result['tool_calls']==2 and result['step_count']==3 and checks==[1] and calls==['OPEN']


@pytest.mark.parametrize('proof',[True,ReadAuthorization({'resource_ref':'T-1'}),
                                 ReadAuthorization({'resource_ref':'T-1','arguments_sha256':'not-a-digest'})])
def test_versioned_reads_require_explicit_current_resource_version_proof(store,proof):
    store.enqueue(request());tool=executor(cache_policy='current-version',authorize=lambda *_:proof,
                                         invoke=lambda *_:pytest.fail('unversioned authorization invoked read'))
    result=worker(store,lambda ctx:tool.execute(ctx,'call-1',proposal())).run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']=='TOOL_AUTHORIZATION_INVALID'
    assert result['tool_calls']==1 and result['step_count']==2 and payload_count()==0


def test_changing_or_revisiting_cached_versions_still_stops_at_shared_tool_budget(store):
    store.enqueue(request());current={'status':'OPEN'};checks=[];calls=[]
    tool=versioned(current,checks,calls)
    def handler(ctx):
        for index in range(10):
            current['status']='CLOSED' if index%2 else 'OPEN'
            tool.execute(ctx,'call-1',proposal())
        return {'outcome':'must-not-finish'}
    result=worker(store,handler).run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']=='BUDGET_EXHAUSTED'
    assert result['tool_calls']==8 and result['step_count']==9 and result['model_calls']==0
    assert checks==[1]*6 and calls==['OPEN','CLOSED'] and payload_count()==2


def test_version_anchor_and_permission_steps_also_consume_the_total_step_budget(store):
    store.enqueue(request(),Limits(max_steps=2));checks=[];calls=[]
    tool=versioned({'status':'OPEN'},checks,calls)
    result=worker(store,lambda ctx:tool.execute(ctx,'call-1',proposal())).run_once('TENANT_A')
    assert result['state']=='FAILED' and result['error_code']=='BUDGET_EXHAUSTED'
    assert result['step_count']==2 and result['tool_calls']==1 and checks==[1] and calls==[] and payload_count()==0
