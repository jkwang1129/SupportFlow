import asyncio
import copy
import json
from uuid import UUID, uuid4

import httpx
import pytest

from ai.runtime.customer_tools import ticket_status_tool
from ai.runtime.source import HEADER, credential
from ai.runtime.tickets import TicketClient, ticket_digest
from ai.runtime.tools import ToolCall, ToolExecutor, ToolRegistry
from ai.runtime.worker import PermanentFailure, TransientFailure
from test_runtime_source import INPUT, RUN, KEY, response
from test_runtime_customer_tools import source_context


PATH = f'/api/customer/internal/runtime/events/{INPUT.source_event_id}/runs/{RUN}/tickets/TK-1'


def envelope():
    value = dict(schema_version='ticket-status-v1', tenant_code=INPUT.tenant, source_event_id=str(INPUT.source_event_id),
                 run_id=str(RUN), snapshot_id='00000000-0000-0000-0000-000000000003', source_hash='a' * 64,
                 ticket_no='TK-1', status=2, updated_at='2026-11-20T12:34:00')
    value['resource_version'] = ticket_digest(
        INPUT, RUN, value['snapshot_id'], value['source_hash'], value['ticket_no'], value['status'], value['updated_at'])
    return dict(code=0, message='成功', data=value)


def invoke(server, ticket='TK-1'):
    client = TicketClient('http://customer.test', KEY, transport=httpx.MockTransport(server))
    return asyncio.run(client.read(INPUT, RUN, ticket))


def test_exact_signed_get_and_bound_status_projection_preserves_database_local_time():
    calls = []

    def server(request):
        calls.append(request)
        assert request.method == 'GET' and request.content == b'' and request.url.raw_path.decode() == PATH
        assert request.headers['X-Tenant-Code'] == INPUT.tenant and 'Authorization' not in request.headers
        expiry = int(request.headers[HEADER].split(':')[2])
        assert request.headers[HEADER] == credential(KEY, 'GET', PATH, INPUT.tenant, b'', expires=expiry)
        return response(envelope())
    value = invoke(server)
    assert value.run_id == RUN and value.event_id == INPUT.source_event_id and value.ticket_no == 'TK-1'
    assert value.snapshot_id == UUID('00000000-0000-0000-0000-000000000003') and value.status == 2
    assert value.updated_at == '2026-11-20T12:34:00' and len(value.result()) == 9 and len(calls) == 1
    assert not {'customer_id', 'tenant_code', 'desc', 'title'}.intersection(value.result())


@pytest.mark.parametrize('field,value', [
    ('tenant_code', 'TENANT_B'), ('source_event_id', str(uuid4())), ('run_id', str(uuid4())), ('snapshot_id', True),
    ('snapshot_id', 'ABCDEF00-0000-0000-0000-000000000003'), ('schema_version', 'ticket-status-v2'),
    ('ticket_no', 'OTHER-CUSTOMER-TICKET'), ('status', True), ('status',
                                                               '2'), ('status', 2.0), ('status', 0), ('status', 6),
    ('source_hash', 'A' * 64), ('source_hash', None), ('resource_version', '0' * 64), ('resource_version', []),
    ('updated_at', '2026-11-20 12:34:00'), ('updated_at', '2026-11-20T12:34:00Z'),
    ('updated_at', '2026-02-30T12:34:00'), ('updated_at', '2026-11-20T12:34:00.1234567'),
    ('private_field', 'private-customer-data'),
])
def test_wrong_resources_types_versions_dates_digests_and_extra_fields_are_rejected(field, value):
    data = envelope()
    data['data'][field] = value
    with pytest.raises(PermanentFailure, match='^TICKET_PAYLOAD_INVALID$'):
        invoke(lambda _: response(data))


@pytest.mark.parametrize('ticket', [None, True, '', '../TK-1', 'TK/1', '%54K-1', 'http://169.254.169.254/', 'T' * 65, '工单1'])
def test_model_input_cannot_form_an_arbitrary_network_path(ticket):
    with pytest.raises(ValueError):
        invoke(lambda _: pytest.fail('invalid destination reached network'), ticket)


@pytest.mark.parametrize('status,code,expected', [
    (404, 40402, 'TICKET_SOURCE_REVOKED'), (404, 40404, 'TICKET_REVOKED'), (409, 40902, 'TICKET_SOURCE_CHANGED'),
    (410, 41002, 'TICKET_SOURCE_EXPIRED'), (413, 41302, 'TICKET_SOURCE_TOO_LARGE'),
    (422, 42202, 'TICKET_SOURCE_INVALID'), (422, 42204, 'TICKET_INVALID_STATE'),
])
def test_business_failure_codes_match_http_status_and_do_not_return_private_data(status, code, expected):
    with pytest.raises(PermanentFailure, match='^' + expected + '$'):
        invoke(lambda _: response(dict(code=code, message='private-detail', data=None), status=status))
    with pytest.raises(PermanentFailure, match='^TICKET_PAYLOAD_INVALID$'):
        invoke(lambda _: response(dict(code=code, message='private-detail', data=None), status=200))


@pytest.mark.parametrize('status,kind,expected', [(401, PermanentFailure, 'TICKET_AUTHORIZATION_DENIED'),
                                                  (403, PermanentFailure, 'TICKET_AUTHORIZATION_DENIED'), (302,
                                                                                                           PermanentFailure, 'TICKET_PROTOCOL_ERROR'),
                                                  (429, TransientFailure, 'TICKET_UNAVAILABLE'), (503, TransientFailure, 'TICKET_UNAVAILABLE')])
def test_protocol_and_transient_errors_have_no_implicit_http_retry(status, kind, expected):
    calls = []
    def server(_): calls.append(1); return response(b'private-provider-detail', status=status)
    with pytest.raises(kind, match='^' + expected + '$'):
        invoke(server)
    assert calls == [1]


@pytest.mark.parametrize('raw', [b'{}', b'[]', b'{"code":true,"data":{}}', b'{"code":0,"code":0,"data":{}}',
                                 b'{"code":40404,"data":{"status":2}}', b'{"code":0,"data":null}', b'\xff'])
def test_malformed_or_duplicate_json_cannot_be_mistaken_for_success(raw):
    with pytest.raises(PermanentFailure, match='TICKET_PAYLOAD_INVALID'):
        invoke(lambda _: response(raw, status=404 if b'40404' in raw else 200))


def test_complete_body_is_bounded_and_timeouts_are_not_provider_exception_text():
    with pytest.raises(PermanentFailure, match='TICKET_RESPONSE_TOO_LARGE'):
        invoke(lambda _: response(b' ' * 8193))

    def timeout(_): raise httpx.ReadTimeout('private-upstream-text')
    with pytest.raises(TransientFailure, match='^TICKET_TIMEOUT$'):
        invoke(timeout)


def tool_executor(server):
    client = TicketClient('http://customer.test', KEY, transport=httpx.MockTransport(server))
    return ToolExecutor(ToolRegistry([ticket_status_tool(client)]), frozenset({'ticket:read'}))


CALL = ToolCall('get_ticket_status', 'ticket-status-v2', {'ticket_no': 'TK-1'})


def test_tool_uses_current_business_proof_and_keeps_tenant_out_of_model_result():
    calls = []
    def server(_): calls.append(1); return response(envelope())
    result = tool_executor(server).execute(source_context(), 'ticket-1', CALL)
    assert result['status'] == 2 and result['ticket_no'] == 'TK-1' and 'tenant_code' not in result
    assert calls == [1, 1]


@pytest.mark.parametrize('fault', ['status', 'source_hash', 'snapshot'])
def test_changed_resource_between_authorization_and_invoke_is_not_reported_as_verified(fault):
    values = [envelope(), envelope()]
    calls = []
    if fault == 'status':
        values[1]['data']['status'] = 4
    elif fault == 'source_hash':
        values[1]['data']['source_hash'] = 'b' * 64
    else:
        values[1]['data']['snapshot_id'] = str(uuid4())
    value = values[1]['data']
    value['resource_version'] = ticket_digest(
        INPUT, RUN, value['snapshot_id'], value['source_hash'], value['ticket_no'], value['status'], value['updated_at'])

    def server(_): calls.append(1); return response(values[len(calls) - 1])
    with pytest.raises(PermanentFailure, match='TOOL_RESULT_INVALID'):
        tool_executor(server).execute(source_context(), 'ticket-1', CALL)
    assert calls == [1, 1]


def test_changed_current_version_refreshes_payload_instead_of_delivering_old_status():
    value = envelope()
    calls = []
    cached = {}
    def server(_): calls.append(1); return response(value)
    ctx = source_context()
    original = ctx.payload_step

    def payload(*args):
        if args[0] not in cached:
            cached[args[0]] = original(*args)
        return copy.deepcopy(cached[args[0]])
    ctx.payload_step = payload
    tools = tool_executor(server)
    tools.execute(ctx, 'ticket-1', CALL)
    value['data']['status'] = 4
    value['data']['resource_version'] = ticket_digest(
        INPUT, RUN, value['data']['snapshot_id'], value['data']['source_hash'], 'TK-1', 4, value['data']['updated_at'])
    assert tools.execute(ctx, 'ticket-1', CALL)['status'] == 4
    assert calls == [1, 1, 1, 1] and len(cached) == 2


def test_old_tool_execution_version_is_not_silently_reinterpreted():
    tools = tool_executor(lambda _: pytest.fail('old execution version reached network'))
    with pytest.raises(PermanentFailure, match='TOOL_VERSION_MISMATCH'):
        tools.execute(source_context(), 'ticket-1', ToolCall('get_ticket_status',
                      'ticket-status-v1', {'ticket_no': 'TK-1'}))


def test_transport_does_not_allow_ticket_protocol_to_target_legacy_or_writable_operations():
    client = TicketClient('http://customer.test', KEY,
                          transport=httpx.MockTransport(lambda _: pytest.fail('invalid transport operation')))
    for method, resource, body in [('POST', 'tickets/TK-1', b''), ('GET', 'tickets/TK-1', b'{}'), ('GET', 'reply', b''), ('GET', 'tickets/TK/1', b'')]:
        with pytest.raises(ValueError):
            asyncio.run(client._exchange(method, INPUT, RUN, resource, body, prefix='TICKET'))
    with pytest.raises(ValueError):
        ticket_status_tool(object())
