import asyncio
from dataclasses import replace
import json
from uuid import uuid4

import httpx
import pytest

from ai.runtime.result import ResultClient
from ai.runtime.reply import arguments_hash
from ai.runtime.source import HEADER, credential
from ai.runtime.types import RunInput, Lease
from ai.runtime.worker import PermanentFailure, TransientFailure

KEY = 'synthetic-reply-pair-' + '0' * 40
SOURCE = RunInput('TENANT_A', uuid4(), 'S1', 1, 42, 'visitor-session:7', 'result-trace')
RUN, SNAPSHOT = uuid4(), uuid4()
LEASE = Lease(SOURCE.tenant, RUN, 'reply-worker', 1)
TEXT = 'synthetic private reply\n"exact" '
DIGEST = arguments_hash(SOURCE, RUN, SNAPSHOT, TEXT)


def data(**changes):
    return {**dict(tenant_code=SOURCE.tenant, source_event_id=str(SOURCE.source_event_id), run_id=str(RUN),
                   snapshot_id=str(SNAPSHOT), schema_version='reply-v1', operation='session.reply', arguments_sha256=DIGEST,
                   message_id='12345', message_no='M12345', seq='2'), **changes}


def response(value=None, *, status=200, code=0):
    raw = json.dumps({'code': code, 'message': 'synthetic private message',
                     'data': data() if value is None else value}).encode()
    return httpx.Response(status, headers={'Content-Type': 'application/json'}, stream=httpx.ByteStream(raw))


def client(handler, **options):
    return ResultClient('http://customer.invalid', KEY, transport=httpx.MockTransport(handler), **options)


def test_exact_wire_body_lease_references_digest_and_receipt_binding():
    calls = []

    def handle(request):
        calls.append(request)
        assert request.url.path.endswith(f'/events/{SOURCE.source_event_id}/runs/{RUN}/reply')
        assert request.headers['X-Tenant-Code'] == SOURCE.tenant and request.headers['X-Request-Id'] == SOURCE.trace_id
        assert 'Authorization' not in request.headers
        expiry = int(request.headers[HEADER].split(':')[2])
        assert request.headers[HEADER] == credential(
            KEY, request.method, request.url.raw_path.decode(), SOURCE.tenant, request.content, expires=expiry)
        body = json.loads(request.content)
        assert body == dict(snapshot_id=str(SNAPSHOT), owner=LEASE.owner, fence=LEASE.fence,
                            arguments_sha256=DIGEST, content=TEXT, version='reply-v1')
        return response()
    receipt = asyncio.run(client(handle).reply(SOURCE, LEASE, SNAPSHOT, TEXT))
    assert receipt.message_id == 12345 and receipt.sequence == 2 and receipt.arguments_sha256 == DIGEST
    assert TEXT not in repr(receipt) and len(calls) == 1


def test_read_only_receipt_can_bind_original_snapshot_and_digest():
    def handle(request):
        assert request.method == 'GET' and not request.content
        return response()
    receipt = asyncio.run(client(handle).receipt(SOURCE, RUN, expected_snapshot_id=SNAPSHOT, expected_hash=DIGEST))
    assert receipt.snapshot_id == SNAPSHOT and receipt.run_id == RUN
    missing = client(lambda _: httpx.Response(
        404, headers={'Content-Type': 'application/json'}, stream=httpx.ByteStream(b'{"code":40403,"data":null}')))
    assert asyncio.run(missing.receipt(SOURCE, RUN)) is None
    with pytest.raises(PermanentFailure):
        asyncio.run(client(lambda _: response(status=404)).receipt(SOURCE, RUN))


@pytest.mark.parametrize('patch', [dict(tenant_code='TENANT_B'), dict(source_event_id=str(uuid4())), dict(run_id=str(uuid4())),
                                   dict(snapshot_id=str(uuid4())), dict(
                                       snapshot_id='ABCDEF00-0000-0000-0000-000000000001'), dict(schema_version='reply-v2'),
                                   dict(operation='ticket.create'), dict(arguments_sha256='b' *
                                                                         64), dict(message_id='0'), dict(message_id=12345),
                                   dict(message_id='9223372036854775808'), dict(seq=True), dict(seq=0), dict(seq=2), dict(seq=2.0), dict(seq='02'), dict(seq='9223372036854775808'), dict(message_no='../other'), dict(extra='authority')])
def test_foreign_substituted_and_invalid_receipts_are_never_accepted(patch):
    with pytest.raises(PermanentFailure):
        asyncio.run(client(lambda _: response(data(**patch))).reply(SOURCE, LEASE, SNAPSHOT, TEXT))


@pytest.mark.parametrize('status,code,expected', [(404, 404, 'REPLY_AUTHORITY_DENIED'), (409, 409, 'REPLY_AUTHORITY_DENIED'),
                                                  (409, 40903, 'REPLY_CONFLICT'), (404, 40402, 'REPLY_RESOURCE_REVOKED'), (410, 41002, 'REPLY_SOURCE_EXPIRED'), (422, 42202, 'REPLY_REJECTED')])
def test_business_denials_are_stable_and_do_not_copy_error_text(status, code, expected):
    with pytest.raises(PermanentFailure) as failure:
        asyncio.run(client(lambda _: response(status=status, code=code)).reply(SOURCE, LEASE, SNAPSHOT, TEXT))
    assert failure.value.code == expected and 'synthetic private' not in str(failure.value)


@pytest.mark.parametrize('status', [401, 403, 429, 500, 503, 302])
def test_http_failures_are_classified_without_hidden_retries(status):
    calls = []
    def handle(request): calls.append(request); return httpx.Response(status, headers={
        'Location': 'http://other.invalid'}, content=b'synthetic private error')
    expected = TransientFailure if status == 429 or status >= 500 else PermanentFailure
    with pytest.raises(expected) as failure:
        asyncio.run(client(handle).reply(SOURCE, LEASE, SNAPSHOT, TEXT))
    assert len(calls) == 1 and 'synthetic private' not in str(failure.value)


def test_oversize_duplicate_trailing_and_wrong_encoding_are_rejected():
    valid = json.dumps(dict(code=0, data=data()), separators=(',', ':')).encode()
    duplicate = valid.replace(b'"seq":"2"', b'"seq":"2","seq":"2"')
    for raw in (b' ' * 8193, b'[]', b'not-json', valid + b'{}', duplicate):
        with pytest.raises(PermanentFailure):
            asyncio.run(client(lambda _, raw=raw: httpx.Response(200, headers={
                        'Content-Type': 'application/json'}, stream=httpx.ByteStream(raw))).reply(SOURCE, LEASE, SNAPSHOT, TEXT))
    for headers in ({'Content-Type': 'text/plain'}, {'Content-Type': 'application/json', 'Content-Encoding': 'gzip'}):
        with pytest.raises(PermanentFailure):
            asyncio.run(client(lambda _, headers=headers: httpx.Response(200, headers=headers,
                        stream=httpx.ByteStream(valid))).reply(SOURCE, LEASE, SNAPSHOT, TEXT))


def test_invalid_lease_and_content_fail_before_any_network_call():
    api = client(lambda _: pytest.fail('Invalid local arguments made a network call'))
    for lease in (replace(LEASE, tenant='TENANT_B'), replace(LEASE, fence=True), replace(LEASE, fence=0),
                  replace(LEASE, fence=9223372036854775808), replace(LEASE, owner='../other')):
        with pytest.raises(ValueError):
            asyncio.run(api.reply(SOURCE, lease, SNAPSHOT, TEXT))
    with pytest.raises(ValueError):
        asyncio.run(api.reply(SOURCE, LEASE, SNAPSHOT, '中' * 2731))


def test_body_timeout_is_ambiguous_not_an_automatic_second_write():
    class Delayed(httpx.AsyncByteStream):
        async def __aiter__(self):
            yield b'{'
            await asyncio.sleep(.3)
            yield b'}'
    calls = []
    def handle(request): calls.append(request); return httpx.Response(
        200, headers={'Content-Type': 'application/json'}, stream=Delayed())
    with pytest.raises(TransientFailure) as failure:
        asyncio.run(client(handle, deadline=.05).reply(SOURCE, LEASE, SNAPSHOT, TEXT))
    assert failure.value.code == 'REPLY_TIMEOUT' and len(calls) == 1


def test_network_error_does_not_leak_endpoint_or_private_cause():
    def handle(request): raise httpx.ConnectError('synthetic-private-network-error', request=request)
    with pytest.raises(TransientFailure) as failure:
        asyncio.run(client(handle).receipt(SOURCE, RUN))
    assert failure.value.code == 'REPLY_NETWORK_ERROR' and failure.value.__cause__ is None


def test_canonical_decimal_long_sequence_preserves_values_above_javascript_precision():
    for value in ('9007199254740993', '9223372036854775807'):
        receipt = asyncio.run(client(lambda _, value=value: response(
            data(seq=value))).reply(SOURCE, LEASE, SNAPSHOT, TEXT))
        assert receipt.sequence == int(value)
