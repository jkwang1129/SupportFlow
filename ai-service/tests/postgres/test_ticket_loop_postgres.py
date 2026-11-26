"""Actual Run/Step/checkpoint/budgets; model and business HTTP are protocol Stubs."""
from dataclasses import replace
import json
import os

import httpx
import psycopg
import pytest

from ai.runtime.approvals import TicketApprovals
from ai.runtime.models import RuntimeChatClient
from ai.runtime.payloads import encode
from ai.runtime.ticket_loop import LOOP_VERSION, TicketReadLoop
from ai.runtime.ticket_prompts import CANARY_PROMPT, STABLE_PROMPT
from ai.runtime.types import Limits
from ai.runtime.worker import RuntimeWorker
from test_runtime_store_postgres import store, request, admin
from test_ticket_loop import fixture, QUERY, REPORT


pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),
                                reason='requires disposable runtime PostgreSQL')


def prepare(store, actions=None, limits=None, **kwargs):
    source = request()
    row = store.enqueue(source, limits or Limits())
    loop, _, http, models, reports, state = fixture(
        actions or [QUERY, REPORT], source_input=source, run_id=row['run_id'], **kwargs)
    return row, loop, http, models, state


def runner(store, loop): return RuntimeWorker(store, {'runtime-v1': loop}, 'ticket-loop-worker')


def artifacts():
    with admin() as db:
        return [json.loads(row['payload_json']) for row in db.execute("SELECT payload_json FROM agent_payload WHERE payload_schema='working-state-v1'").fetchall()]


def test_dynamic_read_loop_finishes_with_durable_verified_report_and_shared_counts(store):
    row, loop, http, models, state = prepare(store)
    result = runner(store, loop).run_once('TENANT_A')
    assert result['state'] == 'SUCCEEDED' and result['result_ref']['outcome'] == 'ticket-status-ready'
    assert len(models) == 2 and (result['model_calls'], result['tool_calls'],
                                 result['step_count'], result['retry_count']) == (2, 5, 10, 0)
    report, = artifacts()
    assert report['status'] == 2 and report['text'] == '工单 TK-1 当前状态：处理中。'
    assert not any(request.url.path.endswith('/reply') for request in http)
    with admin() as db:
        assert db.execute('SELECT count(*) AS n FROM agent_payload').fetchone()['n'] == 5


def test_clarification_finishes_as_an_explicit_artifact_without_a_ticket_query(store):
    row, loop, http, models, state = prepare(store, [{'action': 'clarify'}])
    result = runner(store, loop).run_once('TENANT_A')
    assert result['state'] == 'SUCCEEDED' and result['result_ref']['outcome'] == 'clarification-ready'
    assert (result['model_calls'], result['tool_calls'], result['step_count']) == (1, 2, 5)
    report, = artifacts()
    assert report['kind'] == 'clarification' and report['text'] == '请提供需要查询的工单编号。'
    assert not any('/tickets/' in request.url.path for request in http)


def test_status_changed_during_final_planning_is_reauthorized_and_reported_from_new_business_value(store):
    def change(turn, state):
        if turn == 2:
            state['status'] = 4
    row, loop, http, models, state = prepare(store, after_model=change)
    result = runner(store, loop).run_once('TENANT_A')
    assert result['state'] == 'SUCCEEDED' and (
        result['model_calls'], result['tool_calls'], result['step_count']) == (2, 6, 11)
    report, = artifacts()
    assert report['status'] == 4 and '已解决' in report['text']
    assert json.loads(models[1]['messages'][-1]['content'])['value']['status'] == 2


def test_revoked_owner_after_model_report_decision_does_not_turn_old_fact_into_success(store):
    def revoke(turn, state):
        if turn == 2:
            state['owner'] = False
    row, loop, http, models, state = prepare(store, after_model=revoke)
    result = runner(store, loop).run_once('TENANT_A')
    assert result['state'] == 'FAILED' and result['error_code'] == 'TICKET_REVOKED' and artifacts() == []


def test_guardrail_visitor_text_stays_data_and_cannot_supply_a_report_receipt(store):
    note = '合成访客备注「普通文本」\n边界标记 USER-DATA-01'
    row, loop, http, models, _ = prepare(store, [REPORT], source_text=note)
    result = runner(store, loop).run_once('TENANT_A')
    assert (result['state'], result['error_code'], result['model_calls']) == (
        'FAILED', 'REPORT_EVIDENCE_INVALID', 1)
    policy, user_data = models[0]['messages']
    assert policy['role'] == 'system' and note not in policy['content']
    assert user_data['role'] == 'user'
    assert json.loads(user_data['content']) == {'kind': 'untrusted_user_input', 'text': note}
    assert http and all(request.url.path.endswith('/snapshot') for request in http)
    assert artifacts() == []
    assert all(step['effect'] != 'WRITE' for step in store.steps('TENANT_A', row['run_id']))


def test_guardrail_read_planner_has_no_ticket_creation_capability(store):
    model_decision = {'action': 'tool', 'call': {
        'name': 'create_ticket', 'version': 'ticket-create-v1', 'arguments': {}}}
    row, loop, http, models, _ = prepare(store, [model_decision])
    result = runner(store, loop).run_once('TENANT_A')
    assert (result['state'], result['error_code'], result['model_calls']) == ('FAILED', 'TOOL_UNKNOWN', 1)
    assert 'create_ticket' not in models[0]['messages'][0]['content']
    assert http and all(request.url.path.endswith('/snapshot') for request in http)
    assert artifacts() == []
    assert all(step['effect'] != 'WRITE' for step in store.steps('TENANT_A', row['run_id']))


def test_guardrail_ticket_transport_metadata_is_not_forwarded_to_next_model_turn(store):
    notice = '合成工具返回说明 TOOL-DATA-01\n包含"引号"'
    row, loop, http, models, _ = prepare(store, ticket_message=notice)
    result = runner(store, loop).run_once('TENANT_A')
    assert result['state'] == 'SUCCEEDED' and result['result_ref']['outcome'] == 'ticket-status-ready'
    assert len(models) == 2 and 'TOOL-DATA-01' not in json.dumps(models)
    message = models[1]['messages'][-1]
    assert message['role'] == 'user'
    observed = json.loads(message['content'])
    assert observed['kind'] == 'untrusted_tool_result' and observed['slot'] == 'tool-0'
    assert observed['name'] == 'get_ticket_status' and observed['value']['ticket_no'] == 'TK-1'
    assert set(observed['value']) == {'ticket_no', 'status', 'updated_at'}
    report, = artifacts()
    assert report['status'] == 2 and notice not in report['text']
    assert any('/tickets/' in request.url.path for request in http)
    assert all(step['effect'] != 'WRITE' for step in store.steps('TENANT_A', row['run_id']))


@pytest.mark.parametrize('proposal,code', [
    ({'action': 'report', 'evidence': 'tool-99'}, 'REPORT_EVIDENCE_INVALID'),
    ({'action': 'tool', 'call': {'name': 'shell', 'version': 'v1', 'arguments': {'cmd': 'arbitrary-code'}}}, 'TOOL_UNKNOWN'),
    ({'action': 'tool', 'call': {'name': 'get_ticket_status', 'version': 'ticket-status-v2',
     'arguments': {'ticket_no': 'TK-1', 'tenant': 'TENANT_B'}}}, 'TOOL_ARGUMENTS_INVALID'),
    ({'action': 'report', 'evidence': 'tool-0', 'text': '已退款'}, 'PLANNER_DECISION_INVALID'),
])
def test_invalid_model_actions_have_no_ticket_call_or_verified_artifact(store, proposal, code):
    row, loop, http, models, state = prepare(store, [proposal])
    result = runner(store, loop).run_once('TENANT_A')
    assert result['state'] == 'FAILED' and result['error_code'] == code and artifacts() == []
    assert result['model_calls'] == 1 and result['tool_calls'] == 2 and len(models) == 1
    assert not any('/tickets/' in request.url.path for request in http)


def test_retry_after_model_503_replays_source_context_and_uses_original_budgets(store):
    row, loop, http, models, state = prepare(store, model_faults={1: 503})
    worker = runner(store, loop)
    assert worker.run_once('TENANT_A')['state'] == 'RETRY_WAIT'
    with admin() as db:
        db.execute("UPDATE agent_run SET ready_at=clock_timestamp() WHERE state='RETRY_WAIT'")
    result = worker.run_once('TENANT_A')
    assert result['state'] == 'SUCCEEDED' and len(models) == 3 and len(artifacts()) == 1
    assert (result['model_calls'], result['tool_calls'],
            result['step_count'], result['retry_count']) == (3, 6, 12, 1)


def test_insufficient_model_budget_does_not_make_a_free_final_planner_call(store):
    row, loop, http, models, state = prepare(store, limits=Limits(max_model_calls=1))
    result = runner(store, loop).run_once('TENANT_A')
    assert result['state'] == 'FAILED' and result['error_code'] == 'BUDGET_EXHAUSTED' and len(
        models) == 1 and artifacts() == []
    assert result['model_calls'] == 1 and result['tool_calls'] == 4


def test_final_authorization_is_not_skipped_even_when_only_cached_ticket_data_is_needed(store):
    row, loop, http, models, state = prepare(store, limits=Limits(max_tool_calls=4))
    result = runner(store, loop).run_once('TENANT_A')
    assert result['state'] == 'FAILED' and result['error_code'] == 'BUDGET_EXHAUSTED' and artifacts() == []
    assert len(models) == 2 and result['tool_calls'] == 4 and len(http) == 4


def test_turn_limit_stops_without_a_silent_additional_model_attempt(store):
    row, loop, http, models, state = prepare(store, [QUERY], max_turns=1)
    result = runner(store, loop).run_once('TENANT_A')
    assert result['state'] == 'FAILED' and result['error_code'] == 'PLANNER_TURN_LIMIT' and len(
        models) == 1 and artifacts() == []


@pytest.mark.parametrize('canonical_history', [False, True])
def test_lost_model_checkpoint_commit_ack_resumes_loop_without_repeating_completed_planning(store, canonical_history):
    row, loop, http, models, state = prepare(store, canonical_history=canonical_history)

    class LostAck:
        once = True
        def __getattr__(self, name): return getattr(store, name)

        def complete_payload_step(self, *args):
            value = store.complete_payload_step(*args)
            if self.once and args[2] == 'model-output-v1':
                self.once = False
                raise psycopg.OperationalError('synthetic lost planning acknowledgement')
            return value
    worker = runner(LostAck(), loop)
    assert worker.run_once('TENANT_A')['state'] == 'RETRY_WAIT'
    result = worker.run_once('TENANT_A')
    assert result['state'] == 'SUCCEEDED' and len(models) == 2 and len(artifacts()) == 1
    assert (result['model_calls'], result['tool_calls'],
            result['step_count'], result['retry_count']) == (2, 6, 11, 1)


def test_switching_planner_history_policy_cannot_resume_under_the_old_binding(store):
    row, loop, http, models, state = prepare(store, model_faults={1: 503}, canonical_history=True)
    worker = runner(store, loop)
    assert worker.run_once('TENANT_A')['state'] == 'RETRY_WAIT'
    with admin() as db:
        db.execute("UPDATE agent_run SET ready_at=clock_timestamp() WHERE run_id=%s", (row['run_id'],))
    loop.canonical_history = False
    result = worker.run_once('TENANT_A')
    assert result['state'] == 'FAILED' and result['error_code'] == 'EXECUTION_ERROR'
    assert len(models) == 1 and result['model_calls'] == 1 and artifacts() == []


@pytest.mark.parametrize('first, deployed', [(STABLE_PROMPT, CANARY_PROMPT), (CANARY_PROMPT, STABLE_PROMPT)])
def test_prompt_version_pinned_across_publish_and_rollback_after_lost_checkpoint_ack(store, first, deployed):
    row, loop, _, models, _ = prepare(store, prompt_version=first, model_provider='qwen')
    tenant, run_id = row['tenant_code'], row['run_id']

    class LostModelAck:
        lost = False

        def __getattr__(self, name):
            return getattr(store, name)

        def complete_payload_step(self, *args):
            value = store.complete_payload_step(*args)
            if args[2] == 'model-output-v1' and not self.lost:
                self.lost = True
                raise psycopg.OperationalError('synthetic lost model checkpoint acknowledgement')
            return value

    assert runner(LostModelAck(), loop).run_once(tenant)['state'] == 'RETRY_WAIT'
    assert len(models) == 1
    changed = TicketReadLoop(loop.source_access, loop.tools, loop.model.with_prompt_version(deployed))
    resumed = RuntimeWorker(store, {'runtime-v1': changed}, 'replacement-ticket-worker').run_once(tenant)
    assert resumed['state'] == 'SUCCEEDED' and resumed['model_calls'] == len(models) == 2
    assert models[0]['messages'][0] == models[1]['messages'][0]
    binding = store.trace_delivery_snapshot(tenant, run_id)['trace']['steps']
    assert [item['version'] for item in binding if item.get('operation') == 'ticket.agent'] == [
        LOOP_VERSION if first == STABLE_PROMPT else first]
    assert [item['version'] for item in binding if item.get('operation') == 'model.chat'] == [first, first]
    assert store.trace_delivery_snapshot('TENANT_B', run_id) is None


def test_rollback_with_incompatible_provider_cannot_send_pinned_canary_prompt(store):
    row, loop, _, models, _ = prepare(store, prompt_version=CANARY_PROMPT, model_provider='qwen',
                                      model_faults={1: 503})
    tenant = row['tenant_code']
    assert runner(store, loop).run_once(tenant)['state'] == 'RETRY_WAIT'
    with admin() as db:
        db.execute("UPDATE agent_run SET ready_at=clock_timestamp() WHERE run_id=%s", (row['run_id'],))
    profile = replace(loop.model.profile, provider='deepseek', prompt_version=STABLE_PROMPT)
    probe = RuntimeChatClient(profile, transport=httpx.MockTransport(
        lambda _: pytest.fail('incompatible pinned prompt reached model')))
    changed = TicketReadLoop(loop.source_access, loop.tools, probe)
    resumed = runner(store, changed).run_once(tenant)
    assert (resumed['state'], resumed['error_code'], resumed['model_calls']) == (
        'FAILED', 'TICKET_PROMPT_PROVIDER_UNSUPPORTED', 1)
    assert len(models) == 1


def test_cancellation_after_provider_response_does_not_publish_a_late_plan_or_report(store):
    source = request()
    row = store.enqueue(source)

    def cancel(turn, state):
        if turn == 1:
            store.cancel(source.tenant, row['run_id'])
    loop, _, http, models, reports, state = fixture(
        [QUERY], source_input=source, run_id=row['run_id'], after_model=cancel)
    result = runner(store, loop).run_once('TENANT_A')
    assert result['state'] == 'CANCELLED' and result['model_calls'] == 1 and artifacts() == []
    with admin() as db:
        assert db.execute(
            "SELECT count(*) AS n FROM agent_payload WHERE payload_schema='model-output-v1'").fetchone()['n'] == 0


def test_hash_valid_boolean_status_cannot_masquerade_as_numeric_report_after_resume(store):
    row, loop, http, models, state = prepare(store, limits=Limits(max_steps=16, max_tool_calls=12))
    state['status'] = 1

    class LostReportAck:
        once = True
        def __getattr__(self, name): return getattr(store, name)

        def complete_payload_step(self, *args):
            value = store.complete_payload_step(*args)
            if self.once and args[2] == 'working-state-v1':
                self.once = False
                raise psycopg.OperationalError('synthetic lost report ack')
            return value
    worker = runner(LostReportAck(), loop)
    assert worker.run_once('TENANT_A')['state'] == 'RETRY_WAIT'
    with admin() as db:
        data = json.loads(db.execute(
            "SELECT payload_json FROM agent_payload WHERE payload_schema='working-state-v1'").fetchone()['payload_json'])
        data['status'] = True
        raw, digest = encode('working-state-v1', data)
        db.execute(
            "UPDATE agent_payload SET payload_json=%s,payload_hash=%s WHERE payload_schema='working-state-v1'", (raw, digest))
    result = worker.run_once('TENANT_A')
    assert result['state'] == 'FAILED' and result['error_code'] == 'REPORT_CHECKPOINT_INVALID' and len(models) == 2


@pytest.mark.parametrize('command, enabled, expected', [
    ('/ticket-request\r\n请处理这个问题🙂\r\n原始内容\n保留空格 ', True, 'WAITING_APPROVAL'),
    ('/ticket-request\n请处理这个问题\nsynthetic request', False, 'SUCCEEDED'),
    ('命令示例\n/ticket-request\n普通咨询', True, 'SUCCEEDED'),
    ('/ticket-request\n标题缺正文', True, 'FAILED'),
    ('/ticket-request\n' + ('x' * 129) + '\nsynthetic body', True, 'FAILED'),
])
def test_explicit_ticket_proposal_uses_exact_source_only_when_enabled(store, command, enabled, expected):
    row, loop, http, models, _ = prepare(store, [{'action': 'clarify'}], source_text=command)
    approvals = TicketApprovals(store)
    loop.approvals = approvals if enabled else None
    run_id, tenant = row['run_id'], row['tenant_code']
    result = runner(store, loop).run_once(tenant)
    assert result['state'] == expected
    assert all(step['effect'] != 'WRITE' for step in store.steps(tenant, run_id))
    assert not any('/tickets/' in entry.url.path or entry.url.path.endswith('/ticket-create') for entry in http)
    if expected == 'WAITING_APPROVAL':
        preview = approvals.preview(tenant, run_id, row['source_event_id'])
        assert (preview['title'], preview['content']) == ('请处理这个问题🙂', '原始内容\n保留空格 ')
        assert preview['expires_at'] <= row['deadline_at'] and models == [] and result['model_calls'] == 0
        assert runner(store, loop).run_once(tenant) is None
        assert approvals.preview(tenant, run_id, row['source_event_id']) == preview
    else:
        assert approvals.get(tenant, run_id) is None
        assert len(models) == int(expected == 'SUCCEEDED')
        if expected == 'FAILED':
            assert result['error_code'] == 'TICKET_PROPOSAL_INVALID'


@pytest.mark.parametrize('resume_proposal', [False, True])
def test_explicit_ticket_proposal_checkpoint_survives_worker_restart_without_changing_route(store, resume_proposal):
    row, loop, _, models, _ = prepare(store, [{'action': 'clarify'}], source_text='/ticket-request\n问题\n说明')

    class LostProposalAck:
        once = True

        def __getattr__(self, name):
            return getattr(store, name)

        def complete_payload_step(self, *args):
            value = store.complete_payload_step(*args)
            if self.once and args[2] == 'working-state-v1':
                self.once = False
                raise psycopg.OperationalError('synthetic lost proposal acknowledgement')
            return value

    approvals = TicketApprovals(store)
    loop.approvals = approvals
    assert runner(LostProposalAck(), loop).run_once(row['tenant_code'])['state'] == 'RETRY_WAIT'
    assert approvals.get(row['tenant_code'], row['run_id']) is None
    loop.approvals = approvals if resume_proposal else None
    result = runner(store, loop).run_once(row['tenant_code'])
    assert result['state'] == ('WAITING_APPROVAL' if resume_proposal else 'FAILED')
    assert result['retry_count'] == 1 and result['model_calls'] == 0 and models == [] and len(artifacts()) == 1
    assert bool(approvals.get(row['tenant_code'], row['run_id'])) == resume_proposal
