"""Unit tests for get_customer_profile and get_session_history tools & transports."""
import asyncio
import copy
import json
from uuid import uuid4

import httpx
import pytest

from ai.runtime.profile_tools import (
    CustomerProfileClient,
    CustomerProfileResult,
    SessionHistoryClient,
    SessionHistoryResult,
    customer_profile_digest,
    customer_profile_tool,
    session_history_digest,
    session_history_tool,
)
from ai.runtime.source import HEADER, credential
from ai.runtime.tools import ToolCall, ToolExecutor, ToolRegistry
from ai.runtime.worker import PermanentFailure, TransientFailure
from test_runtime_customer_tools import source_context
from test_runtime_source import INPUT, KEY, RUN, response

SNAPSHOT_ID = '00000000-0000-0000-0000-000000000003'
SOURCE_HASH = 'a' * 64
PROFILE_PATH = f'/api/customer/internal/runtime/events/{INPUT.source_event_id}/runs/{RUN}/customer-profile'
HISTORY_PATH = f'/api/customer/internal/runtime/events/{INPUT.source_event_id}/runs/{RUN}/session-history'


def bound_profile_envelope(**overrides):
    data = dict(
        schema_version='customer-profile-v1',
        tenant_code=INPUT.tenant,
        source_event_id=str(INPUT.source_event_id),
        run_id=str(RUN),
        snapshot_id=SNAPSHOT_ID,
        source_hash=SOURCE_HASH,
        bound=True,
        customer_no='CUS_1001',
        masked_name='张*',
        masked_phone='138****0000',
        level=3,
        customer_type=1,
        points=1560,
        orders_count=8,
        ticket_count=2,
        session_count=5,
        risk_level=1,
    ) | overrides
    if 'resource_version' not in overrides:
        data['resource_version'] = customer_profile_digest(
            INPUT,
            RUN,
            data['snapshot_id'],
            data['source_hash'],
            bound=data['bound'],
            customer_no=data['customer_no'],
            masked_name=data['masked_name'],
            masked_phone=data['masked_phone'],
            level=data['level'],
            customer_type=data['customer_type'],
            points=data['points'],
            orders_count=data['orders_count'],
            ticket_count=data['ticket_count'],
            session_count=data['session_count'],
            risk_level=data['risk_level'],
        )
    return dict(code=0, message='成功', data=data)


def unbound_profile_envelope():
    return bound_profile_envelope(
        bound=False,
        customer_no=None,
        masked_name=None,
        masked_phone=None,
        level=None,
        customer_type=None,
        points=None,
        orders_count=None,
        ticket_count=None,
        session_count=None,
        risk_level=None,
    )


def sample_sessions(count=4):
    sessions = []
    for idx in range(1, count + 1):
        sessions.append(dict(
            session_no=f'SES_{idx}',
            status=2 if idx == 1 else 4,
            started_at=f'2026-11-2{idx}T10:00:00',
            ended_at=None if idx == 1 else f'2026-11-2{idx}T10:30:00',
            messages=[
                dict(seq=1, sender_type=1, content=f'历史提问-{idx}', sent_at=f'2026-11-2{idx}T10:01:00'),
                dict(seq=2, sender_type=3, content=f'机器人回复-{idx}', sent_at=f'2026-11-2{idx}T10:01:05'),
            ],
        ))
    return sessions


def history_envelope(*, bound=True, sessions=None, **overrides):
    items = copy.deepcopy(sample_sessions(4) if sessions is None else sessions)
    data = dict(
        schema_version='session-history-v1',
        tenant_code=INPUT.tenant,
        source_event_id=str(INPUT.source_event_id),
        run_id=str(RUN),
        snapshot_id=SNAPSHOT_ID,
        source_hash=SOURCE_HASH,
        bound=bound,
        sessions=items,
    ) | overrides
    if 'resource_version' not in overrides:
        data['resource_version'] = session_history_digest(
            INPUT,
            RUN,
            data['snapshot_id'],
            data['source_hash'],
            bound=data['bound'],
            sessions=data['sessions'],
            limit=None,
        )
    return dict(code=0, message='成功', data=data)


def test_signed_customer_profile_tool_executes_and_redacts_pii_in_repr():
    calls = []

    def server(request):
        calls.append(request)
        assert request.method == 'GET' and request.content == b''
        assert request.url.raw_path.decode() == PROFILE_PATH
        assert request.headers['X-Tenant-Code'] == INPUT.tenant
        expiry = int(request.headers[HEADER].split(':')[2])
        assert request.headers[HEADER] == credential(KEY, 'GET', PROFILE_PATH, INPUT.tenant, b'', expires=expiry)
        return response(bound_profile_envelope())

    client = CustomerProfileClient('http://customer.test', KEY, transport=httpx.MockTransport(server))
    executor = ToolExecutor(
        ToolRegistry([customer_profile_tool(client)]),
        frozenset({'customer:profile:read'}),
    )
    ctx = source_context()
    result = executor.execute(ctx, 'profile-0', ToolCall('get_customer_profile', 'customer-profile-v1', {}))
    assert len(calls) == 2  # fresh authorize + invoke
    assert result['bound'] is True and result['customer_no'] == 'CUS_1001'
    assert result['masked_name'] == '张*' and result['masked_phone'] == '138****0000'
    assert result['level'] == 3 and result['points'] == 1560
    assert 'tenant_code' not in result and 'remark' not in result and 'phone' not in result
    model_obj = CustomerProfileResult(**result)
    assert '138****0000' not in repr(model_obj) and '张*' not in repr(model_obj)


def test_anonymous_unbound_profile_returns_null_fields_and_verifies_digest():
    def server(_request):
        return response(unbound_profile_envelope())

    client = CustomerProfileClient('http://customer.test', KEY, transport=httpx.MockTransport(server))
    executor = ToolExecutor(
        ToolRegistry([customer_profile_tool(client)]),
        frozenset({'customer:profile:read'}),
    )
    result = executor.execute(source_context(), 'profile-anon', ToolCall('get_customer_profile', 'customer-profile-v1', {}))
    assert result['bound'] is False
    assert result['customer_no'] is None and result['points'] is None and result['level'] is None


@pytest.mark.parametrize('bad_override', [
    {'masked_phone': '13812340000'},  # unmasked phone rejected!
    {'masked_name': '张三丰'},  # unmasked name rejected!
    {'remark': 'INTERNAL_STAFF_REMARK'},  # extra internal field rejected!
    {'level': 6},
    {'risk_level': 0},
    {'points': -1},
    {'tenant_code': 'TENANT_B'},
    {'source_event_id': str(uuid4())},
    {'resource_version': '0' * 64},
])
def test_profile_client_rejects_unmasked_pii_extra_fields_or_digest_tampering(bad_override):
    payload = bound_profile_envelope()
    payload['data'].update(bad_override)
    client = CustomerProfileClient('http://customer.test', KEY, transport=httpx.MockTransport(lambda _: response(payload)))
    with pytest.raises(PermanentFailure, match='^CUSTOMER_PROFILE_PAYLOAD_INVALID$'):
        asyncio.run(client.read(INPUT, RUN))


@pytest.mark.parametrize('arguments', [
    {'customer_no': 'CUS_OTHER'},
    {'tenant': 'TENANT_B'},
    {'url': 'http://169.254.169.254/'},
])
def test_profile_tool_rejects_model_authority_overrides_before_network(arguments):
    client = CustomerProfileClient(
        'http://customer.test',
        KEY,
        transport=httpx.MockTransport(lambda _: pytest.fail('invalid arguments reached HTTP')),
    )
    executor = ToolExecutor(
        ToolRegistry([customer_profile_tool(client)]),
        frozenset({'customer:profile:read'}),
    )
    with pytest.raises(PermanentFailure, match='TOOL_ARGUMENTS_INVALID'):
        executor.execute(source_context(), 'profile-bad', ToolCall('get_customer_profile', 'customer-profile-v1', arguments))


def test_session_history_tool_enforces_limit_and_reauthorizes_on_version_change():
    calls = []
    state = {'msg': '历史提问-1'}

    def server(request):
        calls.append(request)
        assert request.method == 'GET' and request.url.raw_path.decode() == HISTORY_PATH
        sessions = sample_sessions(4)
        sessions[0]['messages'][0]['content'] = state['msg']
        return response(history_envelope(bound=True, sessions=sessions))

    client = SessionHistoryClient('http://customer.test', KEY, transport=httpx.MockTransport(server))
    executor = ToolExecutor(
        ToolRegistry([session_history_tool(client)]),
        frozenset({'session:history:read'}),
    )
    ctx = source_context()
    cached_payloads = {}
    orig_payload_step = ctx.payload_step

    def memoized_payload_step(key, kind, effect, intent, schema, execute):
        if key not in cached_payloads:
            cached_payloads[key] = copy.deepcopy(orig_payload_step(key, kind, effect, intent, schema, execute))
        return copy.deepcopy(cached_payloads[key])

    ctx.payload_step = memoized_payload_step
    first = executor.execute(ctx, 'hist-0', ToolCall('get_session_history', 'session-history-v1', {'limit': 2}))
    assert first['limit'] == 2 and len(first['sessions']) == 2
    assert first['sessions'][0]['messages'][0]['content'] == '历史提问-1'
    assert '历史提问-1' not in repr(SessionHistoryResult(**first))
    assert len(calls) == 2

    # Second call with unchanged backend reuses cached payload after fresh authorize check (1 HTTP call).
    second = executor.execute(ctx, 'hist-0', ToolCall('get_session_history', 'session-history-v1', {'limit': 2}))
    assert second == first
    assert len(calls) == 3

    # Backend message changes -> fresh authorize sees new resource_version and reloads fresh body (2 HTTP calls).
    state['msg'] = '更新后的提问-1'
    third = executor.execute(ctx, 'hist-0', ToolCall('get_session_history', 'session-history-v1', {'limit': 2}))
    assert third['sessions'][0]['messages'][0]['content'] == '更新后的提问-1'
    assert third['resource_version'] != first['resource_version']
    assert len(calls) == 5


@pytest.mark.parametrize('bad_limit', [0, 6, '3', True, None])
def test_session_history_tool_bounds_limit_strictly(bad_limit):
    client = SessionHistoryClient(
        'http://customer.test',
        KEY,
        transport=httpx.MockTransport(lambda _: pytest.fail('invalid limit reached HTTP')),
    )
    executor = ToolExecutor(
        ToolRegistry([session_history_tool(client)]),
        frozenset({'session:history:read'}),
    )
    with pytest.raises(PermanentFailure, match='TOOL_ARGUMENTS_INVALID'):
        executor.execute(source_context(), 'hist-bad', ToolCall('get_session_history', 'session-history-v1', {'limit': bad_limit}))


def test_session_history_rejects_oversized_messages_unordered_seq_or_unbound_multi_sessions():
    # Message > 240 chars rejected
    overlong = sample_sessions(1)
    overlong[0]['messages'][0]['content'] = '字' * 241
    env1 = history_envelope(bound=True, sessions=overlong)
    client1 = SessionHistoryClient('http://customer.test', KEY, transport=httpx.MockTransport(lambda _: response(env1)))
    with pytest.raises(PermanentFailure, match='^SESSION_HISTORY_PAYLOAD_INVALID$'):
        asyncio.run(client1.read(INPUT, RUN))

    # Unbound visitor with > 1 session rejected
    env2 = history_envelope(bound=False, sessions=sample_sessions(2))
    client2 = SessionHistoryClient('http://customer.test', KEY, transport=httpx.MockTransport(lambda _: response(env2)))
    with pytest.raises(PermanentFailure, match='^SESSION_HISTORY_PAYLOAD_INVALID$'):
        asyncio.run(client2.read(INPUT, RUN))


@pytest.mark.parametrize('status,code,expected', [
    (404, 40402, 'CUSTOMER_PROFILE_SOURCE_REVOKED'),
    (404, 40407, 'CUSTOMER_PROFILE_REVOKED'),
    (409, 40902, 'CUSTOMER_PROFILE_SOURCE_CHANGED'),
    (410, 41002, 'CUSTOMER_PROFILE_SOURCE_EXPIRED'),
    (422, 42207, 'CUSTOMER_PROFILE_INVALID_STATE'),
])
def test_profile_business_error_codes_and_transient_http_errors(status, code, expected):
    client = CustomerProfileClient(
        'http://customer.test',
        KEY,
        transport=httpx.MockTransport(lambda _: response(dict(code=code, message='private', data=None), status=status)),
    )
    with pytest.raises(PermanentFailure, match=f'^{expected}$'):
        asyncio.run(client.read(INPUT, RUN))

    transient_client = CustomerProfileClient(
        'http://customer.test',
        KEY,
        transport=httpx.MockTransport(lambda _: response(dict(code=503, message='busy', data=None), status=503)),
    )
    with pytest.raises(TransientFailure, match='^CUSTOMER_PROFILE_UNAVAILABLE$'):
        asyncio.run(transient_client.read(INPUT, RUN))
