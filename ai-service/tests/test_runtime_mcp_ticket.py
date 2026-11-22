"""Fixed MCP tool framing and its placement behind current customer authority."""
import asyncio
import json

import httpx
import pytest

from ai.runtime.customer_tools import ticket_status_tool
from ai.runtime.mcp_ticket import McpTicketClient
from ai.runtime.tickets import TicketClient, ticket_digest
from ai.runtime.tools import ToolCall, ToolExecutor, ToolRegistry
from ai.runtime.worker import PermanentFailure, TransientFailure
from test_runtime_customer_tools import source_context
from test_runtime_source import INPUT, KEY, RUN, response
from test_runtime_tickets import envelope

SERVICE_KEY = 'synthetic-mcp-service-credential-00001'
CALL = ToolCall('get_ticket_status', 'ticket-status-v2', {'ticket_no': 'TK-1'})


def mcp(handler):
    return McpTicketClient('http://127.0.0.1:18451/mcp', SERVICE_KEY, transport=httpx.MockTransport(handler))


def rpc_result(request, value=None, **fields):
    body = json.loads(request.content)
    return {'jsonrpc': '2.0', 'id': body['id'], 'result': {
        'resultType': 'complete', 'content': [], 'structuredContent': value or envelope()['data'],
    }} | fields


@pytest.mark.parametrize('stream', [False, True])
def test_mcp_posts_one_fixed_call_and_checks_protocol_and_business_identity(stream):
    calls = []

    def provider(request):
        calls.append(request)
        wire = json.loads(request.content)
        assert request.method == 'POST' and request.url.path == '/mcp'
        assert request.headers['mcp-protocol-version'] == '2026-07-28'
        assert request.headers['mcp-method'] == wire['method'] == 'tools/call'
        assert request.headers['mcp-name'] == wire['params']['name'] == 'get_ticket_status'
        assert request.headers['Authorization'] == 'Bearer ' + SERVICE_KEY
        assert request.headers['accept'] == 'application/json, text/event-stream'
        assert wire['params']['_meta']['io.modelcontextprotocol/clientCapabilities'] == {}
        assert wire['params']['arguments'] == {'tenant_code': INPUT.tenant, 'run_id': str(RUN),
            'source_event_id': str(INPUT.source_event_id), 'ticket_no': 'TK-1'}
        result = rpc_result(request)
        if stream:
            event = ': heartbeat\r\n\r\ndata: {"jsonrpc":"2.0","method":"notifications/progress"}\r\n\r\n'
            event += 'data: ' + json.dumps(result) + '\r\n\r\n'
            return httpx.Response(200, headers={'Content-Type': 'text/event-stream'}, content=event)
        return response(result)

    ticket = asyncio.run(mcp(provider).read(INPUT, RUN, 'TK-1'))
    assert ticket.status == 2 and ticket.run_id == RUN and len(calls) == 1


@pytest.mark.parametrize('fault,code', [
    ('http503', 'MCP_UNAVAILABLE'), ('redirect', 'MCP_REQUEST_REJECTED'), ('id', 'MCP_PAYLOAD_INVALID'),
    ('body', 'MCP_PAYLOAD_INVALID'), ('incomplete', 'MCP_PAYLOAD_INVALID'), ('oversized', 'MCP_PAYLOAD_INVALID'),
    ('tool_error', 'MCP_TOOL_REJECTED'), ('wrong_tenant', 'TICKET_PAYLOAD_INVALID'),
    ('timeout', 'MCP_TIMEOUT'), ('unclosed_sse', 'MCP_PAYLOAD_INVALID'),
])
def test_mcp_failures_are_bounded_classified_and_do_not_retry(fault, code):
    calls = []

    def provider(request):
        calls.append(request.url.path)
        result = rpc_result(request)
        if fault == 'http503':
            return response(result, status=503)
        if fault == 'timeout':
            raise httpx.ReadTimeout('synthetic transport deadline', request=request)
        if fault == 'redirect':
            return httpx.Response(307, headers={'Location': 'http://127.0.0.1:18451/unexpected'})
        if fault == 'id':
            result['id'] = 'other'
        if fault == 'body':
            return httpx.Response(200, content=b'plain text')
        if fault == 'incomplete':
            result['result']['resultType'] = 'input_required'
        if fault == 'oversized':
            result['padding'] = 'a'*17000
        if fault == 'unclosed_sse':
            return httpx.Response(200, headers={'Content-Type': 'text/event-stream'}, content='data: '+'x'*17000)
        if fault == 'tool_error':
            result['result']['isError'] = True
        if fault == 'wrong_tenant':
            result['result']['structuredContent']['tenant_code'] = 'FOREIGN'
        return response(result)

    failure = TransientFailure if fault in ('http503', 'timeout') else PermanentFailure
    with pytest.raises(failure, match=f'^{code}$'):
        asyncio.run(mcp(provider).read(INPUT, RUN, 'TK-1'))
    assert calls == ['/mcp']


def test_mcp_cannot_bypass_customer_source_or_supply_a_different_status():
    calls = []

    def authority(request):
        calls.append('authority')
        return response(envelope())

    def provider(request):
        calls.append('mcp')
        value = envelope()['data']
        value['status'] = 4
        value['resource_version'] = ticket_digest(INPUT, RUN, value['snapshot_id'], value['source_hash'],
                                                    'TK-1', value['status'], value['updated_at'])
        return response(rpc_result(request, value))

    def executor(customer):
        direct = TicketClient('http://customer.test', KEY, transport=httpx.MockTransport(customer))
        return ToolExecutor(ToolRegistry([ticket_status_tool(direct, mcp_client=mcp(provider))]), frozenset({'ticket:read'}))

    with pytest.raises(PermanentFailure, match='TOOL_RESULT_INVALID'):
        executor(authority).execute(source_context(), 'ticket-1', CALL)
    assert calls == ['authority', 'mcp']
    calls.clear()
    with pytest.raises(PermanentFailure, match='TICKET_REVOKED'):
        executor(lambda _: response({'code': 40404, 'data': None}, status=404)).execute(source_context(), 'ticket-1', CALL)
    assert calls == []


@pytest.mark.parametrize('endpoint', ['http://remote.invalid/mcp', 'https://host.invalid/other',
                                     'https://user@host.invalid/mcp', 'https://host.invalid/mcp?query=1'])
def test_mcp_endpoint_requires_fixed_trusted_configuration(endpoint):
    with pytest.raises(ValueError):
        McpTicketClient(endpoint, SERVICE_KEY)
