"""Deterministic planner protocol/domain adapters. Not model-quality evaluation."""
import copy
import json
import hashlib

import httpx
import pytest

from ai.runtime.customer_tools import current_message_tool,ticket_status_tool
from ai.runtime.models import RuntimeChatClient
from ai.runtime.source import SourceClient
from ai.runtime.tickets import TicketClient,ticket_digest
from ai.runtime.ticket_loop import TicketReadLoop,decision
from ai.runtime.ticket_prompts import STABLE_PROMPT
from ai.runtime.tools import ToolExecutor,ToolRegistry
from ai.runtime.worker import PermanentFailure
from test_runtime_models import profile,answer
from test_runtime_source import INPUT,RUN,KEY,envelope as source_envelope
from test_runtime_tickets import envelope as ticket_envelope
from test_chat_transport import response
from test_runtime_customer_tools import source_context


QUERY={'action':'tool','call':{'name':'get_ticket_status','version':'ticket-status-v2','arguments':{'ticket_no':'TK-1'}}}
REPORT={'action':'report','evidence':'tool-0'}


def fixture(actions,*,max_turns=4,after_model=None,wrong_source=False,source_input=INPUT,run_id=RUN,model_faults=None,
            canonical_history=False,source_text=None,ticket_message=None,prompt_version=STABLE_PROMPT,
            model_provider='fixture'):
    source=source_envelope();state={'status':2,'owner':True};http=[];models=[];reports=[]
    payload=json.loads(source['data']['payload_json'])
    if source_text is not None:
        payload['untrusted_input']['content'] = source_text
    payload['trusted_source'].update(tenant_code=source_input.tenant,source_event_id=str(source_input.source_event_id),run_id=str(run_id),
        session_no=source_input.session_no,message_id=str(source_input.message_id),event_seq=source_input.event_seq,principal_ref=source_input.principal_ref)
    raw=json.dumps(payload,ensure_ascii=False,sort_keys=True,separators=(',',':'))
    source['data'].update(tenant_code=source_input.tenant,source_event_id=str(source_input.source_event_id),run_id=str(run_id),
                          payload_json=raw,payload_hash=hashlib.sha256(raw.encode()).hexdigest())
    def customer(request):
        http.append(request)
        if request.url.path.endswith('/snapshot'):return response(source)
        if not state['owner']:return response(dict(code=40404,message='private foreign owner',data=None),status=404)
        data=ticket_envelope();value=data['data']
        if ticket_message is not None:
            data['message'] = ticket_message
        value.update(tenant_code=source_input.tenant,source_event_id=str(source_input.source_event_id),run_id=str(run_id),
                     snapshot_id=source['data']['snapshot_id'],source_hash='b'*64 if wrong_source else source['data']['payload_hash'],status=state['status'])
        value['resource_version']=ticket_digest(source_input,run_id,value['snapshot_id'],value['source_hash'],'TK-1',value['status'],value['updated_at'])
        return response(data)
    transport=httpx.MockTransport(customer)
    access=ToolExecutor(ToolRegistry([current_message_tool(SourceClient('http://customer.test',KEY,transport=transport))]),frozenset({'session:read'}))
    tools=ToolExecutor(ToolRegistry([ticket_status_tool(TicketClient('http://customer.test',KEY,transport=transport))]),frozenset({'ticket:read'}))
    served=[]
    def provider(request):
        models.append(json.loads(request.content))
        if after_model:after_model(len(models),state)
        if model_faults and len(models) in model_faults:return response(b'synthetic provider unavailable',status=model_faults[len(models)])
        action=actions[len(served)];served.append(1);data=answer()
        data['choices'][0]['message']['content']=action if isinstance(action,str) else json.dumps(action)
        return response(data)
    model=RuntimeChatClient(profile(tenant=source_input.tenant,prompt_version=prompt_version,provider=model_provider),
                            transport=httpx.MockTransport(provider))
    loop=TicketReadLoop(access,tools,model,max_turns=max_turns,canonical_history=canonical_history)
    ctx=source_context();original=ctx.payload_step
    ctx.prior_step_intent = lambda key: None
    def payload(key,kind,effect,intent,schema,execute):
        value=original(key,kind,effect,intent,schema,execute)
        if kind=='INTERNAL' and value.get('schema_version')=='ticket-report-v1':reports.append(copy.deepcopy(value))
        return value
    ctx.payload_step=payload
    return loop,ctx,http,models,reports,state


def test_model_driven_query_and_stop_create_a_fact_report_without_sending_business_reply():
    loop,ctx,http,models,reports,state=fixture([QUERY,REPORT])
    refs=loop(ctx)
    assert refs['outcome']=='ticket-status-ready' and refs['resource_ref']=='TK-1'
    assert len(models)==2 and len(reports)==1 and reports[0]['text']=='工单 TK-1 当前状态：处理中。'
    assert all(request.method in ('GET','POST') and not request.url.path.endswith('/reply') for request in http)
    observed=json.loads(models[1]['messages'][-1]['content'])
    assert set(observed['value'])=={'ticket_no','status','updated_at'}
    assert 'tenant_code' not in observed['value'] and 'snapshot_id' not in observed['value']
    assert profile().api_key not in str(models)


def test_final_report_reauthorizes_and_uses_new_status_instead_of_model_prose():
    def change(turn,state):
        if turn==2:state['status']=4
    loop,ctx,http,models,reports,state=fixture([QUERY,REPORT],after_model=change)
    assert loop(ctx)['outcome']=='ticket-status-ready'
    assert reports[0]['status']==4 and reports[0]['text']=='工单 TK-1 当前状态：已解决。'
    assert json.loads(models[1]['messages'][-1]['content'])['value']['status']==2


def test_owner_revocation_during_planning_cannot_produce_report_from_old_observation():
    def revoke(turn,state):
        if turn==2:state['owner']=False
    loop,ctx,http,models,reports,state=fixture([QUERY,REPORT],after_model=revoke)
    with pytest.raises(PermanentFailure,match='TICKET_REVOKED'):loop(ctx)
    assert reports==[]


def test_clarification_has_a_fixed_template_and_does_not_query_a_ticket():
    loop,ctx,http,models,reports,state=fixture([{'action':'clarify'}])
    assert loop(ctx)['outcome']=='clarification-ready'
    assert reports[0]['text']=='请提供需要查询的工单编号。'
    assert len(models)==1 and not any('/tickets/' in request.url.path for request in http)


@pytest.mark.parametrize('content',['null','[]','not json','{"action":"clarify","action":"clarify"}',
    '{"action":"report","evidence":"tool-0","text":"已退款"}',
    '{"action":"create_ticket"}', '{"action":"clarify","tenant":"TENANT_B"}'])
def test_planner_cannot_invent_execution_claims_or_change_the_decision_protocol(content):
    with pytest.raises(PermanentFailure,match='PLANNER_DECISION_INVALID'):decision(content)


@pytest.mark.parametrize('proposal,code',[
    ({'action':'report','evidence':'tool-99'},'REPORT_EVIDENCE_INVALID'),
    ({'action':'tool','call':{'name':'shell','version':'v1','arguments':{'cmd':'arbitrary-code'}}},'TOOL_UNKNOWN'),
    ({'action':'tool','call':{'name':'get_ticket_status','version':'ticket-status-v2','arguments':{'ticket_no':'TK-1','tenant':'TENANT_B'}}},'TOOL_ARGUMENTS_INVALID'),
    ({'action':'report','evidence':'tool-0','text':'已创建工单'},'PLANNER_DECISION_INVALID'),
])
def test_bad_model_actions_stop_before_ticket_http_or_report(proposal,code):
    loop,ctx,http,models,reports,state=fixture([proposal])
    with pytest.raises(PermanentFailure,match=code):loop(ctx)
    assert len(models)==1 and reports==[] and not any('/tickets/' in request.url.path for request in http)


def test_turn_limit_has_no_implicit_extra_model_call():
    loop,ctx,http,models,reports,state=fixture([QUERY,QUERY],max_turns=2)
    with pytest.raises(PermanentFailure,match='PLANNER_TURN_LIMIT'):loop(ctx)
    assert len(models)==2 and reports==[]


def test_business_result_must_match_bootstrapped_source_snapshot_even_if_its_own_digest_is_valid():
    loop,ctx,http,models,reports,state=fixture([QUERY,REPORT],wrong_source=True)
    with pytest.raises(PermanentFailure,match='REPORT_EVIDENCE_INVALID'):loop(ctx)
    assert reports==[]


def test_canonical_history_preserves_decision_and_report_with_less_duplicated_text():
    padded = ' '*4736 + json.dumps(QUERY, indent=2) + '\n'*8
    standard, compact = [fixture([padded, REPORT], canonical_history=mode) for mode in (False, True)]
    for loop,ctx,_,_,_,_ in (standard, compact):
        assert loop(ctx)['outcome'] == 'ticket-status-ready'
    raw, compressed = [entry[3][1]['messages'] for entry in (standard, compact)]
    assert raw[:2] == compressed[:2] and raw[3:] == compressed[3:]
    assert raw[2]['content'] == padded and json.loads(compressed[2]['content']) == QUERY
    assert len(compressed[2]['content'].encode()) < len(padded.encode())
    facts = ('ticket_no', 'status', 'updated_at', 'text')
    assert [standard[4][0][key] for key in facts] == [compact[4][0][key] for key in facts]
    assert len(standard[2]) == len(compact[2])
