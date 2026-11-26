"""Narrow knowledge wire contract; synthetic data, no model or database."""
import asyncio
import copy
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from threading import Event, Thread
from uuid import UUID, uuid4

import httpx
import pytest

from ai.runtime.knowledge import KnowledgeClient, knowledge_digest
from ai.runtime.source import HEADER, credential
from ai.runtime.worker import PermanentFailure, TransientFailure
from test_runtime_source import INPUT, RUN, KEY, response

SNAPSHOT = UUID('00000000-0000-0000-0000-000000000003')
SOURCE_HASH = 'a' * 64
QUERY_HASH = 'b' * 64
MODEL = 'fixture:fixture-embedding'
VECTOR = [.25] + [0.0] * 1023
PATH = f'/api/customer/internal/runtime/events/{INPUT.source_event_id}/runs/{RUN}/knowledge/search'


def parameters():
    return dict(snapshot_id=SNAPSHOT, source_hash=SOURCE_HASH, query_sha256=QUERY_HASH, embedding_model=MODEL,
                vector=VECTOR, top_k=5, doc_ids=None)


def envelope(request):
    body = json.loads(request.content)
    hit = dict(doc_id='9223372036854775807', chunk_id='42', title='synthetic title',
               snippet='synthetic snippet', score_ppm=750000, embedding_model=MODEL)
    value = dict(schema_version='knowledge-search-v1', policy_version='visitor-published-v1', tenant_code=INPUT.tenant,
                 source_event_id=str(INPUT.source_event_id), run_id=str(RUN), snapshot_id=str(SNAPSHOT), source_hash=SOURCE_HASH,
                 request_sha256=hashlib.sha256(request.content).hexdigest(), query_sha256=QUERY_HASH, embedding_model=MODEL,
                 hits=[] if body['doc_ids'] == [] else [hit])
    value['resource_version'] = knowledge_digest(value)
    return dict(code=0, message='成功', data=value)


def invoke(server, **changes):
    client = KnowledgeClient('http://customer.test', KEY, transport=httpx.MockTransport(server))
    return asyncio.run(client.search(INPUT, RUN, **(parameters() | changes)))


def test_exact_signed_post_and_immutable_minimal_projection():
    calls = []

    def server(request):
        calls.append(request)
        assert request.method == 'POST' and request.url.raw_path.decode() == PATH
        assert set(json.loads(request.content)) == {'snapshot_id',
                                                    'query_sha256', 'embedding_model', 'vector', 'top_k', 'doc_ids'}
        assert request.headers['X-Tenant-Code'] == INPUT.tenant and 'Authorization' not in request.headers
        expiry = int(request.headers[HEADER].split(':')[2])
        assert request.headers[HEADER] == credential(
            KEY, 'POST', PATH, INPUT.tenant, request.content, expires=expiry)
        return response(envelope(request))
    value = invoke(server)
    assert len(calls) == 1 and len(value.hits) == 1 and value.hits[0].doc_id == '9223372036854775807'
    assert value.hits[0].score_ppm == 750000 and value.snapshot_id == SNAPSHOT
    projection = value.result()
    assert len(projection) == 11 and not {'tenant_code', 'vector',
                                          'payload_json', 'customer_ref'}.intersection(projection)
    assert 'synthetic snippet' not in repr(
        value) + repr(value.hits[0]) and 'synthetic title' not in repr(value.hits[0])
    with pytest.raises((TypeError, AttributeError)):
        value.hits[0].snippet = 'changed'
    projection['hits'][0]['snippet'] = 'changed'
    assert value.hits[0].snippet == 'synthetic snippet'


@pytest.mark.parametrize('scope', [None, [], [9223372036854775807, 9223372036854775807]])
def test_null_empty_and_deduplicated_document_scope_remain_distinct(scope):
    calls = []
    def server(request): calls.append(json.loads(request.content)); return response(envelope(request))
    value = invoke(server, doc_ids=scope)
    assert calls[0]['doc_ids'] == (None if scope is None else list(dict.fromkeys(scope)))
    assert len(value.hits) == (0 if scope == [] else 1)


@pytest.mark.parametrize('timestamp', [1795233000000, '1795233000000'])
def test_java_envelope_metadata_is_accepted_but_not_added_to_model_projection(timestamp):
    def server(request): return response(envelope(request) | dict(
        requestId='synthetic-trace', timestamp=timestamp))
    assert 'requestId' not in invoke(server).result()
    def bad(request): return response(envelope(request) | dict(private='PRIVATE BODY'))
    with pytest.raises(PermanentFailure, match='^KNOWLEDGE_PAYLOAD_INVALID$'):
        invoke(bad)


@pytest.mark.parametrize('field,value', [
    ('snapshot_id', True), ('snapshot_id', 'bad'), ('source_hash',
                                                    'A' * 64), ('source_hash', None), ('query_sha256', 'x'),
    ('embedding_model', 'local-hash'), ('embedding_model', 'http://outside'), ('embedding_model', 'p:' + 'a' * 96),
    ('top_k', True), ('top_k', 1.0), ('top_k', 0), ('top_k', 6),
    ('vector', None), ('vector', [.25] * 1023), ('vector', [0.0] * 1024), ('vector', [1e-12] * 1024),
    ('vector', [True] + [0.0] * 1023), ('vector', [float('nan')] + [0.0] * 1023), ('vector', [1e7] + [0.0] * 1023),
    ('doc_ids', True), ('doc_ids', '42'), ('doc_ids', [True]), ('doc_ids', [1.0]), ('doc_ids', [0]),
    ('doc_ids', [-1]), ('doc_ids', [9223372036854775808]), ('doc_ids', list(range(1, 202))),
])
def test_bad_trusted_query_fails_before_network(field, value):
    with pytest.raises(ValueError):
        invoke(lambda _: pytest.fail('invalid query reached network'), **{field: value})


@pytest.mark.parametrize('field,value', [
    ('schema_version', 'knowledge-search-v2'), ('policy_version', 'admin-preview'), ('tenant_code', 'TENANT_B'),
    ('source_event_id', str(uuid4())), ('run_id', str(uuid4())), ('snapshot_id', str(uuid4())),
    ('source_hash', 'c' * 64), ('request_sha256', 'c' *
                                64), ('query_sha256', 'c' * 64), ('embedding_model', 'other:model'),
    ('resource_version', '0' * 64), ('private', 'PRIVATE BODY'), ('hits', None),
])
def test_response_binding_and_unknown_fields_are_rejected(field, value):
    def server(request):
        data = envelope(request)
        data['data'][field] = value
        return response(data)
    with pytest.raises(PermanentFailure, match='^KNOWLEDGE_PAYLOAD_INVALID$'):
        invoke(server)


@pytest.mark.parametrize('field,value', [
    ('doc_id', 42), ('doc_id', '01'), ('doc_id', '0'), ('doc_id', '9223372036854775808'), ('chunk_id', True),
    ('score_ppm', True), ('score_ppm', 750000.0), ('score_ppm', 1000001), ('score_ppm', -1000001),
    ('title', 'x' * 201), ('snippet', ''), ('snippet', ' '), ('snippet', '🙂' * 1025), ('snippet', '\ud800'),
    ('embedding_model', 'other:model'), ('private', 'PRIVATE BODY'),
])
def test_invalid_hit_is_denied_even_with_recomputed_projection_digest(field, value):
    def server(request):
        data = envelope(request)
        data['data']['hits'][0][field] = value
        # Bad Unicode cannot be represented in valid UTF-8 JSON; preserve escape
        # so the SDK must reject the decoded surrogate explicitly.
        if field == 'snippet' and value == '\ud800':
            return response(json.dumps(data).encode())
        data['data']['resource_version'] = knowledge_digest(data['data'])
        return response(data)
    with pytest.raises(PermanentFailure, match='^KNOWLEDGE_PAYLOAD_INVALID$'):
        invoke(server)


@pytest.mark.parametrize('fault', ['duplicate', 'too_many', 'scope', 'empty_scope', 'tampered_text', 'descending'])
def test_result_count_scope_uniqueness_and_integrity_are_not_just_json_shape(fault):
    def server(request):
        data = envelope(request)
        value = data['data']
        hit = value['hits'][0] if value['hits'] else dict(
            doc_id='9223372036854775807', chunk_id='42', title='synthetic title', snippet='synthetic snippet', score_ppm=750000, embedding_model=MODEL)
        if fault == 'duplicate':
            value['hits'] = [hit, copy.deepcopy(hit)]
        elif fault == 'too_many':
            value['hits'] = [dict(hit, chunk_id=str(i + 1)) for i in range(6)]
        elif fault in ('scope', 'empty_scope'):
            value['hits'] = [hit]
        elif fault == 'tampered_text':
            hit['snippet'] = 'changed'
        elif fault == 'descending':
            value['hits'] = [hit, dict(hit, chunk_id='43', score_ppm=800000)]
        if fault != 'tampered_text':
            value['resource_version'] = knowledge_digest(value)
        return response(data)
    with pytest.raises(PermanentFailure, match='^KNOWLEDGE_PAYLOAD_INVALID$'):
        invoke(server, **({'doc_ids': [1]} if fault
               == 'scope' else {'doc_ids': []} if fault == 'empty_scope' else {}))


@pytest.mark.parametrize('status,code,expected', [
    (404, 40402, 'KNOWLEDGE_SOURCE_REVOKED'), (409, 40902, 'KNOWLEDGE_SOURCE_CHANGED'),
    (409, 40905, 'KNOWLEDGE_SNAPSHOT_MISMATCH'), (410, 41002, 'KNOWLEDGE_SOURCE_EXPIRED'),
    (413, 41302, 'KNOWLEDGE_SOURCE_TOO_LARGE'), (422, 42202, 'KNOWLEDGE_SOURCE_INVALID'),
    (422, 42205, 'KNOWLEDGE_RESULT_INVALID'),
])
def test_business_errors_are_classified_and_status_must_match(status, code, expected):
    with pytest.raises(PermanentFailure, match='^' + expected + '$'):
        invoke(lambda _: response(dict(code=code, message='PRIVATE BODY', data=None), status=status))
    with pytest.raises(PermanentFailure, match='^KNOWLEDGE_PAYLOAD_INVALID$'):
        invoke(lambda _: response(dict(code=code, message='PRIVATE BODY', data=None)))


@pytest.mark.parametrize('status,kind,code', [(401, PermanentFailure, 'KNOWLEDGE_AUTHORIZATION_DENIED'),
                                              (403, PermanentFailure, 'KNOWLEDGE_AUTHORIZATION_DENIED'), (302,
                                                                                                          PermanentFailure, 'KNOWLEDGE_PROTOCOL_ERROR'),
                                              (429, TransientFailure, 'KNOWLEDGE_UNAVAILABLE'), (503, TransientFailure, 'KNOWLEDGE_UNAVAILABLE')])
def test_transport_error_has_no_raw_body_or_hidden_retry(status, kind, code):
    calls = []
    with pytest.raises(kind, match='^' + code + '$'):
        invoke(lambda _: calls.append(1) or response(b'PRIVATE BODY', status=status))
    assert calls == [1]


@pytest.mark.parametrize('raw', [b'{}', b'[]', b'{"code":true,"data":{}}', b'{"code":0,"code":0,"data":{}}',
                                 b'{"code":0,"data":null}', b'{"code":0,"data":NaN}', b'\xff'])
def test_malformed_duplicate_or_nonfinite_json_is_rejected(raw):
    with pytest.raises(PermanentFailure, match='^KNOWLEDGE_PAYLOAD_INVALID$'):
        invoke(lambda _: response(raw))


def test_body_size_timeout_and_transport_operation_whitelist():
    with pytest.raises(PermanentFailure, match='^KNOWLEDGE_RESPONSE_TOO_LARGE$'):
        invoke(lambda _: response(b' ' * 65537))

    def timeout(_): raise httpx.ReadTimeout('PRIVATE BODY')
    with pytest.raises(TransientFailure, match='^KNOWLEDGE_TIMEOUT$'):
        invoke(timeout)
    client = KnowledgeClient('http://customer.test', KEY,
                             transport=httpx.MockTransport(lambda _: pytest.fail('invalid operation reached HTTP')))
    for method, resource in [('GET', 'knowledge/search'), ('POST', 'snapshot'), ('POST', 'reply'), ('POST', 'knowledge/other')]:
        with pytest.raises(ValueError):
            asyncio.run(client._exchange(method, INPUT, RUN, resource, b'{}', prefix='KNOWLEDGE'))


@pytest.mark.parametrize('mode,code', [('normal', None), ('redirect', 'KNOWLEDGE_PROTOCOL_ERROR'), ('slow', 'KNOWLEDGE_TIMEOUT')])
def test_actual_tcp_uses_signed_route_and_total_body_deadline_without_environment_proxy(monkeypatch, mode, code):
    monkeypatch.setenv('HTTP_PROXY', 'http://127.0.0.1:1')
    monkeypatch.setenv('ALL_PROXY', 'http://127.0.0.1:1')
    monkeypatch.setenv('NO_PROXY', '')
    received = []
    release = Event()

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            raw = self.rfile.read(int(self.headers['Content-Length']))
            received.append((self.path, self.headers.get('Authorization'), raw))
            body = json.dumps(envelope(httpx.Request('POST', 'http://customer.test', content=raw)),
                              ensure_ascii=False).encode()
            self.send_response(302 if mode == 'redirect' else 200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            if mode == 'redirect':
                self.send_header('Location', 'http://127.0.0.1:1/private')
            self.end_headers()
            if mode == 'slow':
                release.wait(2)
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass

        def log_message(self, *_): pass
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = Thread(target=lambda: server.serve_forever(poll_interval=.01), daemon=True)
    thread.start()
    try:
        client = KnowledgeClient(f'http://127.0.0.1:{server.server_port}',
                                 KEY, deadline=1 if mode == 'slow' else 2)
        if code:
            with pytest.raises(TransientFailure if mode == 'slow' else PermanentFailure, match='^' + code + '$'):
                asyncio.run(client.search(INPUT, RUN, **parameters()))
        else:
            assert asyncio.run(client.search(INPUT, RUN, **parameters())).hits[0].score_ppm == 750000
        assert len(received) == 1 and received[0][0] == PATH and received[0][1] is None
    finally:
        release.set()
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
