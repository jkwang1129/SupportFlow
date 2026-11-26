"""Unit tests for IntentRouter (Workflow + Agent Loop + Human Handoff) and CustomerAgentLoop."""
import copy
import hashlib
import json
import socket
from threading import Event
from types import SimpleNamespace
from uuid import UUID, uuid4

import httpx
import psycopg
import pytest

from ai.runtime.agent_loop import (
    AGENT_LOOP_VERSION,
    AGENT_STABLE_PROMPT,
    AGENT_TAPE_VERSION,
    AgentTapeMismatch,
    CustomerAgentLoop,
    agent_planner_policy,
    parse_agent_decision,
    replay_agent_plan,
)
from ai.runtime.approvals import TicketApprovals
from ai.runtime.customer_tools import current_message_tool, ticket_status_tool
from ai.runtime.embeddings import RuntimeEmbeddingClient
from ai.runtime.intent_router import IntentRouter, RouteDecision, RouteTarget, TurnSignals
from ai.runtime.knowledge import KnowledgeClient, knowledge_digest
from ai.runtime.knowledge_tools import knowledge_search_tool
from ai.runtime.mcp_ticket import McpTicketClient
from ai.runtime.models import RuntimeChatClient
from ai.runtime.profile_tools import (
    CustomerProfileClient,
    SessionHistoryClient,
    customer_profile_digest,
    customer_profile_tool,
    session_history_digest,
    session_history_tool,
)
from ai.runtime.source import SourceClient
from ai.runtime.tickets import TicketClient, ticket_digest
from ai.runtime.tools import ToolExecutor, ToolRegistry
from ai.runtime.types import fingerprint
from ai.runtime.worker import ApprovalPending, ExecutionContext, PermanentFailure
from test_chat_transport import response
from test_runtime_customer_tools import source_context
from test_runtime_embeddings import answer as embedding_answer, profile as embedding_profile
from test_runtime_models import answer, profile
from test_runtime_source import INPUT, KEY, RUN, envelope as source_envelope
from test_runtime_tickets import envelope as ticket_envelope


# ---------------------------------------------------------------------------
# Part 1: Hybrid IntentRouter tests
# ---------------------------------------------------------------------------


@pytest.mark.parametrize('query,metadata,expected_route,expected_tools', [
    ('七天无理由退货规则是什么？', None, RouteTarget.WORKFLOW, ('search_knowledge',)),
    ('开发票需要多久？', {'turn_count': 1, 'emotion': 'neutral'}, RouteTarget.WORKFLOW, ('search_knowledge',)),
    ('不用转人工，帮我看下退货地址在哪', None, RouteTarget.WORKFLOW, ('search_knowledge',)),
    ('请帮我转人工客服处理', None, RouteTarget.HUMAN_HANDOFF, ()),
    ('我要去12315投诉你们欺诈消费者！', None, RouteTarget.HUMAN_HANDOFF, ()),
    ('这个问题怎么还没解决', {'emotion': '愤怒', 'emotion_score': 0.92}, RouteTarget.HUMAN_HANDOFF, ()),
    ('还是不行', {'risk_level': 3, 'repeat_unresolved_turns': 2}, RouteTarget.HUMAN_HANDOFF, ()),
    ('帮我查一下工单 TK-202611 的最新进度', None, RouteTarget.AGENT_LOOP, ('get_ticket_status',)),
    ('我的会员等级和剩余积分是多少？', None, RouteTarget.AGENT_LOOP, ('get_customer_profile',)),
    ('我上次聊天咨询过的事情，帮我看看记录', None, RouteTarget.AGENT_LOOP, ('get_session_history',)),
    (
        '查一下我的金卡会员等级和积分，另外退换货政策是什么？',
        None,
        RouteTarget.AGENT_LOOP,
        ('search_knowledge', 'get_customer_profile'),
    ),
    ('我的设备无法开机，请帮我创建一个工单跟进', None, RouteTarget.AGENT_LOOP, ()),
    ('/ticket-request\n设备故障\n需要工程师排查', None, RouteTarget.AGENT_LOOP, ()),
])
def test_intent_router_deterministic_routing_and_suggested_tools(query, metadata, expected_route, expected_tools):
    router = IntentRouter()
    decision = router.route(query, metadata=metadata)
    assert isinstance(decision, RouteDecision)
    assert decision.route == expected_route
    assert decision.suggested_tools == expected_tools
    assert 0.0 <= decision.confidence <= 1.0
    with pytest.raises(AttributeError):
        decision.route = RouteTarget.WORKFLOW  # type: ignore[misc]


def test_intent_router_filters_suggested_tools_against_registered_subset_and_supports_llm_classifier():
    calls = []

    def classifier(query: str, signals: TurnSignals):
        calls.append((query, signals.turn_count))
        return json.dumps({
            'route': 'AGENT_LOOP',
            'reason': 'implicit_account_lookup',
            'confidence': 0.88,
            'suggested_tools': ['get_customer_profile', 'get_session_history'],
        })

    router = IntentRouter(
        available_tools=('search_knowledge', 'get_customer_profile'),
        classifier=classifier,
        classifier_threshold=0.8,
    )
    # Nuanced utterance without explicit keywords triggers bounded LLM classifier.
    res = router.route('看看我够不够格换那个礼品', metadata={'turn_count': 2})
    assert res.route == RouteTarget.AGENT_LOOP
    assert res.reason == 'llm_implicit_account_lookup'
    # Unregistered get_session_history is stripped automatically!
    assert res.suggested_tools == ('get_customer_profile',)
    assert len(calls) == 1

    # Malformed classifier output fails open to deterministic WORKFLOW when fail_open=True.
    bad_router = IntentRouter(classifier=lambda _q, _s: 'not-json', fail_open=True)
    fallback = bad_router.route('随便问一下你们营业时间')
    assert fallback.route == RouteTarget.WORKFLOW


# ---------------------------------------------------------------------------
# Part 2: Multi-tool CustomerAgentLoop fixture & tests
# ---------------------------------------------------------------------------


def build_agent_fixture(
    actions,
    *,
    source_text='请帮我核实会员积分、上次会话以及工单 TK-1 状态',
    max_turns=6,
    canonical_history=True,
    enable_approvals=False,
    use_mcp_ticket=False,
    after_model=None,
    include_knowledge=False,
):
    source = source_envelope()
    payload = json.loads(source['data']['payload_json'])
    payload['untrusted_input']['content'] = source_text
    payload['trusted_source'].update(
        tenant_code=INPUT.tenant,
        source_event_id=str(INPUT.source_event_id),
        run_id=str(RUN),
        session_no=INPUT.session_no,
        message_id=str(INPUT.message_id),
        event_seq=INPUT.event_seq,
        principal_ref=INPUT.principal_ref,
    )
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
    source['data'].update(
        tenant_code=INPUT.tenant,
        source_event_id=str(INPUT.source_event_id),
        run_id=str(RUN),
        payload_json=raw,
        payload_hash=hashlib.sha256(raw.encode()).hexdigest(),
    )
    snapshot_id = source['data']['snapshot_id']
    source_hash = source['data']['payload_hash']

    state = {'ticket_status': 2, 'points': 1200, 'owner': True, 'paused': []}
    http_requests = []
    mcp_requests = []
    model_requests = []
    reports = []

    def customer_server(request: httpx.Request):
        http_requests.append(request)
        path = request.url.path
        if path.endswith('/snapshot'):
            return response(source)
        if not state['owner']:
            code = 40404 if '/tickets/' in path else 40407
            return response(dict(code=code, message='revoked', data=None), status=404)
        if '/tickets/' in path:
            env = ticket_envelope()
            val = env['data']
            val.update(
                tenant_code=INPUT.tenant,
                source_event_id=str(INPUT.source_event_id),
                run_id=str(RUN),
                snapshot_id=snapshot_id,
                source_hash=source_hash,
                status=state['ticket_status'],
            )
            val['resource_version'] = ticket_digest(
                INPUT, RUN, snapshot_id, source_hash, 'TK-1', val['status'], val['updated_at']
            )
            return response(env)
        if path.endswith('/customer-profile'):
            prof = dict(
                schema_version='customer-profile-v1',
                tenant_code=INPUT.tenant,
                source_event_id=str(INPUT.source_event_id),
                run_id=str(RUN),
                snapshot_id=snapshot_id,
                source_hash=source_hash,
                bound=True,
                customer_no='CUS_88',
                masked_name='王*',
                masked_phone='139****1234',
                level=4,
                customer_type=1,
                points=state['points'],
                orders_count=15,
                ticket_count=2,
                session_count=6,
                risk_level=1,
            )
            prof['resource_version'] = customer_profile_digest(
                INPUT, RUN, snapshot_id, source_hash,
                bound=True, customer_no='CUS_88', masked_name='王*', masked_phone='139****1234',
                level=4, customer_type=1, points=state['points'], orders_count=15,
                ticket_count=2, session_count=6, risk_level=1,
            )
            return response(dict(code=0, message='ok', data=prof))
        if path.endswith('/session-history'):
            sessions = [dict(
                session_no=INPUT.session_no,
                status=2,
                started_at='2026-11-25T09:00:00',
                ended_at=None,
                messages=[dict(seq=1, sender_type=1, content='上次咨询退货单号', sent_at='2026-11-25T09:01:00')],
            )]
            hist = dict(
                schema_version='session-history-v1',
                tenant_code=INPUT.tenant,
                source_event_id=str(INPUT.source_event_id),
                run_id=str(RUN),
                snapshot_id=snapshot_id,
                source_hash=source_hash,
                bound=True,
                sessions=sessions,
                resource_version=session_history_digest(
                    INPUT, RUN, snapshot_id, source_hash, bound=True, sessions=sessions, limit=None
                ),
            )
            return response(dict(code=0, message='ok', data=hist))
        if path.endswith('/knowledge/search'):
            body = json.loads(request.content)
            req_sha = hashlib.sha256(request.content).hexdigest()
            kb_data = dict(
                schema_version='knowledge-search-v1',
                policy_version='visitor-published-v1',
                tenant_code=INPUT.tenant,
                source_event_id=str(INPUT.source_event_id),
                run_id=str(RUN),
                snapshot_id=snapshot_id,
                source_hash=source_hash,
                request_sha256=req_sha,
                query_sha256=body['query_sha256'],
                embedding_model=body['embedding_model'],
                hits=[dict(
                    doc_id='101',
                    chunk_id='201',
                    title='铂金会员退换货政策',
                    snippet='铂金会员享受30天免费上门取件退换服务。',
                    score_ppm=910000,
                    embedding_model=body['embedding_model'],
                )],
            )
            kb_data['resource_version'] = knowledge_digest(kb_data)
            return response(dict(code=0, message='ok', data=kb_data))
        raise AssertionError(f'Unexpected path: {path}')

    def mcp_server(request: httpx.Request):
        mcp_requests.append(request)
        rpc = json.loads(request.content)
        structured = dict(
            schema_version='ticket-status-v1',
            tenant_code=INPUT.tenant,
            source_event_id=str(INPUT.source_event_id),
            run_id=str(RUN),
            snapshot_id=snapshot_id,
            source_hash=source_hash,
            ticket_no='TK-1',
            status=state['ticket_status'],
            updated_at='2026-11-20T12:34:00',
        )
        structured['resource_version'] = ticket_digest(
            INPUT, RUN, snapshot_id, source_hash, 'TK-1', structured['status'], structured['updated_at']
        )
        return response({
            'jsonrpc': '2.0',
            'id': rpc['id'],
            'result': {
                'resultType': 'complete',
                'isError': False,
                'content': [{'type': 'text', 'text': 'ok'}],
                'structuredContent': structured,
            },
        })

    customer_transport = httpx.MockTransport(customer_server)
    source_client = SourceClient('http://customer.test', KEY, transport=customer_transport)
    access = ToolExecutor(ToolRegistry([current_message_tool(source_client)]), frozenset({'session:read'}))

    mcp_client = None
    transport_hash = None
    if use_mcp_ticket:
        mcp_client = McpTicketClient(
            'http://127.0.0.1:14002/mcp',
            'mcp-distinct-service-secret-00000000001',
            transport=httpx.MockTransport(mcp_server),
        )
        transport_hash = mcp_client.definition_hash()

    shim = source_context()
    row = shim.run | dict(owner=shim.lease.owner, fence=shim.lease.fence)
    ctx = ExecutionContext(SimpleNamespace(heartbeat=lambda *_a, **_k: None), row, Event(), 30)
    ctx.steps = shim.steps
    ctx.step = shim.step
    ctx.prior_step_intent = lambda _key: None
    cached_payloads = {}
    original_payload_step = shim.payload_step

    def capturing_payload_step(key, kind, effect, intent, schema, execute):
        if key not in cached_payloads:
            cached_payloads[key] = copy.deepcopy(original_payload_step(key, kind, effect, intent, schema, execute))
        val = copy.deepcopy(cached_payloads[key])
        if kind == 'INTERNAL' and isinstance(val, dict) and val.get('schema_version') == 'agent-report-v1':
            reports.append(copy.deepcopy(val))
        return val

    ctx.payload_step = capturing_payload_step

    registered_tools = [
        customer_profile_tool(CustomerProfileClient('http://customer.test', KEY, transport=customer_transport)),
        session_history_tool(SessionHistoryClient('http://customer.test', KEY, transport=customer_transport)),
        ticket_status_tool(
            TicketClient('http://customer.test', KEY, transport=customer_transport),
            mcp_client=mcp_client,
        ),
    ]
    scopes = {'customer:profile:read', 'session:history:read', 'ticket:read'}

    if include_knowledge:
        def embed_server(_req: httpx.Request):
            return response(embedding_answer())

        emb_client = RuntimeEmbeddingClient(
            embedding_profile(tenant=INPUT.tenant),
            transport=httpx.MockTransport(embed_server),
        )
        kb_client = KnowledgeClient('http://customer.test', KEY, transport=customer_transport)
        registered_tools.append(knowledge_search_tool(ctx, source_client, kb_client, emb_client))
        scopes.add('knowledge:read')

    tools = ToolExecutor(ToolRegistry(registered_tools), frozenset(scopes))

    served = []

    def provider_server(request: httpx.Request):
        model_requests.append(json.loads(request.content))
        if after_model:
            after_model(len(model_requests), state)
        action = actions[len(served)]
        served.append(1)
        data = answer()
        data['choices'][0]['message']['content'] = action if isinstance(action, str) else json.dumps(action, ensure_ascii=False)
        return response(data)

    model = RuntimeChatClient(
        profile(tenant=INPUT.tenant, prompt_version=AGENT_STABLE_PROMPT, provider='fixture'),
        transport=httpx.MockTransport(provider_server),
    )

    approvals = None
    if enable_approvals:
        class StubApprovalControl(TicketApprovals):
            def __init__(self):
                super().__init__(SimpleNamespace())

            def pause(self, c, snap_id, title, content, *, wait_seconds=60):
                # Execute exact checkpoint validation & persistence before raising ApprovalPending!
                from ai.runtime.ticket_creation import arguments_hash
                from ai.runtime.tools import ToolCall as TC, ToolIdentity
                identity = ToolIdentity.from_context(c)
                digest = arguments_hash(identity.source, identity.run_id, snap_id, title, content)
                TC('create_ticket', 'ticket-create-v1', dict(title=title, content=content))
                intent = dict(
                    operation='ticket.create',
                    version='ticket-create-v1',
                    resource_ref=identity.source.principal_ref,
                    input_ref=str(snap_id),
                    arguments_sha256=digest,
                )
                expected = dict(title=title, content=content, snapshot_id=str(snap_id), arguments_sha256=digest)
                checked = c.payload_step('ticket-approval-plan', 'INTERNAL', 'NONE', intent, 'working-state-v1', lambda: expected)
                assert checked == expected
                state['paused'].append(dict(snapshot_id=snap_id, title=title, content=content, digest=digest, wait_seconds=wait_seconds))
                raise ApprovalPending()

        approvals = StubApprovalControl()

    loop = CustomerAgentLoop(
        access,
        tools,
        model,
        max_turns=max_turns,
        canonical_history=canonical_history,
        transport_hash=transport_hash,
        approvals=approvals,
    )
    return loop, ctx, http_requests, mcp_requests, model_requests, reports, state


def test_multi_tool_agent_loop_combines_profile_history_knowledge_mcp_ticket_and_reauthorizes_on_report():
    actions = [
        {'action': 'tool', 'call': {'name': 'get_customer_profile', 'version': 'customer-profile-v1', 'arguments': {}}},
        {'action': 'tool', 'call': {'name': 'get_session_history', 'version': 'session-history-v1', 'arguments': {'limit': 2}}},
        {'action': 'tool', 'call': {'name': 'search_knowledge', 'version': 'knowledge-live-v1', 'arguments': {'query': '铂金会员退换货政策', 'top_k': 3}}},
        {'action': 'tool', 'call': {'name': 'get_ticket_status', 'version': 'ticket-status-v2', 'arguments': {'ticket_no': 'TK-1'}}},
        {
            'action': 'report',
            'summary': '您是铂金会员，享受30天免费上门取件，工单 TK-1 最新状态已更新。',
            'evidence': ['tool-0', 'tool-1', 'tool-2', 'tool-3'],
        },
    ]

    def mutate_before_report(turn_no, state):
        if turn_no == 5:
            # Mutate backend state before final report to prove fresh re-authorization!
            state['ticket_status'] = 4
            state['points'] = 1880

    loop, ctx, _http, mcp_reqs, models, reports, _state = build_agent_fixture(
        actions,
        use_mcp_ticket=True,
        include_knowledge=True,
        after_model=mutate_before_report,
    )
    refs = loop(ctx)
    assert refs['outcome'] == 'agent-report-ready'
    assert len(models) == 5 and len(reports) == 1
    assert len(mcp_reqs) == 2  # initial invoke + report re-verification invoke!
    evidence = {item['tool']: item['projection'] for item in reports[0]['evidence']}
    assert evidence['get_customer_profile']['points'] == 1880
    assert evidence['get_ticket_status']['status'] == 4
    assert evidence['get_ticket_status']['status_label'] == '已解决'
    assert evidence['get_session_history']['sessions'][0]['messages'][0]['content'] == '上次咨询退货单号'
    assert evidence['search_knowledge']['hits'][0]['title'] == '铂金会员退换货政策'


def test_revoked_authorization_during_report_reverification_aborts_before_report_commit():
    actions = [
        {'action': 'tool', 'call': {'name': 'get_customer_profile', 'version': 'customer-profile-v1', 'arguments': {}}},
        {'action': 'report', 'summary': '您的积分充足。', 'evidence': ['tool-0']},
    ]

    def revoke_on_second_turn(turn_no, state):
        if turn_no == 2:
            state['owner'] = False

    loop, ctx, _http, _mcp, _models, reports, _state = build_agent_fixture(actions, after_model=revoke_on_second_turn)
    with pytest.raises(PermanentFailure, match='CUSTOMER_PROFILE_REVOKED'):
        loop(ctx)
    assert reports == []


def test_natural_language_and_shortcut_hitl_ticket_proposals_pause_into_waiting_approval():
    # 1. Natural language proposal after inspecting customer profile evidence
    nl_actions = [
        {'action': 'tool', 'call': {'name': 'get_customer_profile', 'version': 'customer-profile-v1', 'arguments': {}}},
        {
            'action': 'propose_ticket',
            'title': '铂金会员退货上门取件异常',
            'content': '客户反馈预约取件未响应，申请创建工单跟进。',
            'evidence': ['tool-0'],
        },
    ]
    loop, ctx, _http, _mcp, models, reports, state = build_agent_fixture(nl_actions, enable_approvals=True)
    with pytest.raises(ApprovalPending):
        loop(ctx)
    assert len(models) == 2 and reports == [] and len(state['paused']) == 1
    assert state['paused'][0]['title'] == '铂金会员退货上门取件异常'
    assert len(state['paused'][0]['digest']) == 64

    # 2. Explicit /ticket-request shortcut still works without invoking the LLM!
    shortcut_loop, shortcut_ctx, _h2, _m2, shortcut_models, _r2, shortcut_state = build_agent_fixture(
        [],
        source_text='/ticket-request\n发票重开申请\n抬头填写有误，申请重开电子发票',
        enable_approvals=True,
    )
    with pytest.raises(ApprovalPending):
        shortcut_loop(shortcut_ctx)
    assert shortcut_models == [] and len(shortcut_state['paused']) == 1
    assert shortcut_state['paused'][0]['title'] == '发票重开申请'


def test_propose_ticket_rejects_disabled_approvals_unobserved_evidence_or_invalid_bounds():
    proposal = {
        'action': 'propose_ticket',
        'title': '建单标题',
        'content': '建单详情',
        'evidence': [],
    }
    # Approvals disabled -> TICKET_PROPOSAL_DISABLED
    loop_disabled, ctx_disabled, *_ = build_agent_fixture([proposal], enable_approvals=False)
    with pytest.raises(PermanentFailure, match='TICKET_PROPOSAL_DISABLED'):
        loop_disabled(ctx_disabled)

    # Unobserved evidence slot -> PROPOSAL_EVIDENCE_INVALID
    bad_ev = dict(proposal, evidence=['tool-3'])
    loop_ev, ctx_ev, *_ = build_agent_fixture([bad_ev], enable_approvals=True)
    with pytest.raises(PermanentFailure, match='PROPOSAL_EVIDENCE_INVALID'):
        loop_ev(ctx_ev)

    # Blank title -> TICKET_PROPOSAL_INVALID
    blank_title = dict(proposal, title='   ')
    loop_blank, ctx_blank, *_ = build_agent_fixture([blank_title], enable_approvals=True)
    with pytest.raises(PermanentFailure, match='TICKET_PROPOSAL_INVALID'):
        loop_blank(ctx_blank)


def test_clarify_and_escalate_decisions_produce_bounded_structured_outcomes():
    loop1, ctx1, _, _, _, reports1, _ = build_agent_fixture([{'action': 'clarify', 'reason': 'missing_ticket_no'}])
    assert loop1(ctx1)['outcome'] == 'clarification-ready'
    assert reports1[0]['kind'] == 'clarification' and reports1[0]['reason'] == 'missing_ticket_no'

    loop2, ctx2, _, _, _, reports2, _ = build_agent_fixture([{'action': 'escalate', 'reason': 'vip_dispute'}])
    assert loop2(ctx2)['outcome'] == 'escalation-ready'
    assert reports2[0]['kind'] == 'escalation' and reports2[0]['reason'] == 'vip_dispute'


@pytest.mark.parametrize('bad_json', [
    '[]',
    '{"action":"report","summary":"ok","evidence":[]}',
    '{"action":"report","summary":"ok","evidence":["tool-0","tool-0"]}',
    '{"action":"propose_ticket","title":"t","content":"c","evidence":[],"tenant":"TENANT_B"}',
    '{"action":"escalate"}',
])
def test_parse_agent_decision_rejects_malformed_or_authority_escalating_payloads(bad_json):
    with pytest.raises(PermanentFailure, match='PLANNER_DECISION_INVALID'):
        parse_agent_decision(bad_json)


# ---------------------------------------------------------------------------
# Part 3: Read-only offline replay verification (replay_agent_plan)
# ---------------------------------------------------------------------------


def test_replay_agent_plan_verifies_multi_tool_reauthorization_and_proposal_without_io(monkeypatch):
    def forbid_io(*_args, **_kwargs):
        pytest.fail('Offline replay must not touch network sockets or PostgreSQL')

    monkeypatch.setattr(socket, 'socket', forbid_io)
    monkeypatch.setattr(psycopg, 'connect', forbid_io)

    catalog = [
        {'name': 'get_customer_profile', 'version': 'customer-profile-v1', 'scope': 'customer:profile:read', 'effect': 'READ'},
        {'name': 'get_ticket_status', 'version': 'ticket-status-v2', 'scope': 'ticket:read', 'effect': 'READ'},
    ]
    policy = agent_planner_policy(catalog, AGENT_STABLE_PROMPT)
    question = '查询我的积分和工单 TK-9'
    pack = lambda v: json.dumps(v, ensure_ascii=False, sort_keys=True, separators=(',', ':'))

    call0 = {'action': 'tool', 'call': {'name': 'get_customer_profile', 'version': 'customer-profile-v1', 'arguments': {}}}
    prof_val = {
        'bound': True, 'customer_no': 'CUS_1', 'masked_name': '李*', 'masked_phone': '138****1111',
        'level': 2, 'customer_type': 1, 'points': 500, 'orders_count': 3,
        'ticket_count': 1, 'session_count': 2, 'risk_level': 1,
    }
    call1 = {'action': 'tool', 'call': {'name': 'get_ticket_status', 'version': 'ticket-status-v2', 'arguments': {'ticket_no': 'TK-9'}}}
    ticket_val = {'ticket_no': 'TK-9', 'status': 2, 'updated_at': '2026-11-26T08:00:00'}

    msg0 = [
        {'role': 'system', 'content': policy},
        {'role': 'user', 'content': pack({'kind': 'untrusted_user_input', 'text': question})},
    ]
    msg1 = msg0 + [
        {'role': 'assistant', 'content': pack(call0)},
        {'role': 'user', 'content': pack({'kind': 'untrusted_tool_result', 'slot': 'tool-0', 'name': 'get_customer_profile', 'value': prof_val})},
    ]
    msg2 = msg1 + [
        {'role': 'assistant', 'content': pack(call1)},
        {
            'role': 'user',
            'content': pack({
                'kind': 'untrusted_tool_result',
                'slot': 'tool-1',
                'name': 'get_ticket_status',
                'value': ticket_val | {'status_label': '处理中'},
            }),
        },
    ]
    report_reply = pack({'action': 'report', 'summary': '积分500，工单处理中。', 'evidence': ['tool-0', 'tool-1']})

    tape = {
        'schema_version': AGENT_TAPE_VERSION,
        'loop_version': AGENT_LOOP_VERSION,
        'prompt_version': AGENT_STABLE_PROMPT,
        'policy_sha256': fingerprint(policy),
        'catalog': catalog,
        'source_text': question,
        'max_turns': 6,
        'canonical_history': True,
        'allow_ticket_proposal': True,
        'events': [
            {'kind': 'model', 'input_sha256': fingerprint(msg0), 'reply': pack(call0)},
            {'kind': 'tool_read', 'slot': 'tool-0', 'name': 'get_customer_profile', 'version': 'customer-profile-v1', 'arguments': {}, 'result': prof_val},
            {'kind': 'model', 'input_sha256': fingerprint(msg1), 'reply': pack(call1)},
            {'kind': 'tool_read', 'slot': 'tool-1', 'name': 'get_ticket_status', 'version': 'ticket-status-v2', 'arguments': {'ticket_no': 'TK-9'}, 'result': ticket_val},
            {'kind': 'model', 'input_sha256': fingerprint(msg2), 'reply': report_reply},
            # Re-verification reads for both cited evidence slots!
            {'kind': 'tool_read', 'slot': 'tool-0', 'name': 'get_customer_profile', 'version': 'customer-profile-v1', 'arguments': {}, 'result': prof_val},
            {'kind': 'tool_read', 'slot': 'tool-1', 'name': 'get_ticket_status', 'version': 'ticket-status-v2', 'arguments': {'ticket_no': 'TK-9'}, 'result': ticket_val | {'status': 4}},
        ],
        'expected': {'outcome': 'agent-report-ready', 'error_code': None, 'model_calls': 3, 'tool_reads': 4},
    }
    result = replay_agent_plan(tape)
    assert result['passed'] is True
    assert result['observed'] == {'outcome': 'agent-report-ready', 'error_code': None, 'model_calls': 3, 'tool_reads': 4}

    # Tampered policy digest is caught deterministically
    tampered = copy.deepcopy(tape)
    tampered['policy_sha256'] = '0' * 64
    with pytest.raises(AgentTapeMismatch, match='POLICY_CHANGED'):
        replay_agent_plan(tampered)
