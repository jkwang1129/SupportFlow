"""Actual source SDK/HTTPX contract through the read-tool executor, no model."""
import asyncio
from dataclasses import asdict
import hashlib
import json
from uuid import uuid4

import httpx
import pytest

from ai.runtime.customer_tools import CurrentMessageResult, current_message_tool
from ai.runtime.source import SourceClient
from ai.runtime.tools import ReadAuthorization, ToolCall, ToolExecutor, ToolRegistry
from ai.runtime.types import Lease
from ai.runtime.worker import PermanentFailure, TransientFailure
from test_runtime_source import INPUT, RUN, KEY, PATH, envelope, response
from test_runtime_tools import context


def source_context():
    ctx = context()
    ctx.run = asdict(INPUT)
    ctx.run['tenant_code'] = ctx.run.pop('tenant')
    ctx.run['run_id'] = RUN
    ctx.lease = Lease(INPUT.tenant, RUN, 'source-tool-worker', 1)
    return ctx


def executor(handler, **kwargs):
    client = SourceClient('http://customer.test', KEY, transport=httpx.MockTransport(handler))
    return ToolExecutor(ToolRegistry([current_message_tool(client, **kwargs)]), frozenset({'session:read'}))


CALL = ToolCall('get_current_message', 'source-v1', {})


def test_fixed_source_routes_signed_by_sdk_and_fresh_proof_binds_text_snapshot_and_run():
    calls, value = [], envelope()
    def server(request):
        calls.append(request)
        assert request.url.raw_path.decode() == PATH
        assert request.headers['X-Tenant-Code'] == INPUT.tenant and 'Authorization' not in request.headers
        return response(value)
    ctx = source_context()
    result = executor(server).execute(ctx, 'source-1', CALL)
    assert [request.method for request in calls] == ['POST', 'GET']
    assert result['text'] == '测试来源文本' and result['role'] == 'user'
    assert result['source_event_id'] == str(INPUT.source_event_id) and result['run_id'] == str(RUN)
    assert 'customer_ref' not in result and 'tenant_code' not in result and 'payload_json' not in result
    assert '测试来源文本' not in str(ctx.steps)
    assert '测试来源文本' not in repr(CurrentMessageResult(**result))


@pytest.mark.parametrize('change', ['snapshot', 'text', 'hash'])
def test_source_changed_between_authorization_and_read_is_not_a_verified_result(change):
    calls, value = [], envelope()
    def server(request):
        calls.append(1)
        if len(calls) == 2:
            if change == 'snapshot':
                value['data']['snapshot_id'] = str(uuid4())
            else:
                payload = json.loads(value['data']['payload_json'])
                if change == 'text':
                    payload['untrusted_input']['content'] = '替换的正文'
                else:
                    payload['trusted_source']['customer_ref'] = '42'
                raw = json.dumps(payload, ensure_ascii=False)
                value['data']['payload_json'] = raw
                value['data']['payload_hash'] = hashlib.sha256(raw.encode()).hexdigest()
        return response(value)
    with pytest.raises(PermanentFailure, match='^TOOL_RESULT_INVALID$'):
        executor(server).execute(source_context(), 'source-1', CALL)
    assert calls == [1, 1]


@pytest.mark.parametrize('arguments', [{'tenant': 'TENANT_B'}, {'session_no': 'OTHER'},
                                      {'url': 'http://169.254.169.254/'}, {'principal_ref': 'admin:1'}])
def test_model_cannot_choose_a_different_source_or_http_destination(arguments):
    with pytest.raises(PermanentFailure, match='TOOL_ARGUMENTS_INVALID'):
        executor(lambda *_: pytest.fail('invalid proposal reached HTTP')).execute(
            source_context(), 'source-1', ToolCall('get_current_message', 'source-v1', arguments))


@pytest.mark.parametrize('code,expected', [(40402, 'SOURCE_REVOKED'), (40902, 'SOURCE_CHANGED'), (41002, 'SOURCE_EXPIRED')])
def test_current_business_denial_stops_before_loading_any_tool_body(code, expected):
    calls = []
    def server(request):
        calls.append(1)
        return response({'code': code, 'message': 'private-source-detail', 'data': None})
    with pytest.raises(PermanentFailure, match='^' + expected + '$'):
        executor(server).execute(source_context(), 'source-1', CALL)
    assert calls == [1]


def test_outer_deadline_bounds_even_an_async_transport_that_ignores_httpx_timeouts():
    calls = []
    async def delayed(request):
        calls.append(1)
        await asyncio.sleep(.2)
        return response(envelope())
    with pytest.raises(TransientFailure, match='^TOOL_TIMEOUT$'):
        executor(delayed, timeout_seconds=.05).execute(source_context(), 'source-1', CALL)
    assert calls == [1]


def test_current_authorization_proof_is_detached_immutable_and_reference_only():
    refs = {'resource_ref': 'snapshot-1'}
    grant = ReadAuthorization(refs)
    refs['resource_ref'] = 'other'
    assert grant.references['resource_ref'] == 'snapshot-1'
    with pytest.raises(TypeError):
        grant.references['resource_ref'] = 'other'
    with pytest.raises(ValueError):
        ReadAuthorization({'content': 'private source body'})
    with pytest.raises(ValueError):
        current_message_tool(object())


@pytest.mark.parametrize('fault', ['cached-text', 'new-current-snapshot'])
def test_cached_body_must_still_match_this_executions_fresh_business_proof(fault):
    value, calls, cached = envelope(), [], []
    def server(request):
        calls.append(request.method)
        return response(value)
    ctx = source_context()
    tool = executor(server)
    original = ctx.payload_step
    def payload(*args):
        if not cached:
            cached.append(original(*args))
        return dict(cached[0])
    ctx.payload_step = payload
    tool.execute(ctx, 'source-1', CALL)
    if fault == 'cached-text':
        cached[0]['text'] = '篡改缓存正文'
    else:
        value['data']['snapshot_id'] = str(uuid4())
    with pytest.raises(PermanentFailure, match='^TOOL_RESULT_INVALID$'):
        tool.execute(ctx, 'source-1', CALL)
    assert calls == ['POST', 'GET', 'POST']
