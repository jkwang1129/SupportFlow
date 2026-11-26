"""Existing chat entry point: bounded one-attempt protocol and no raw payload logs."""
import asyncio
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
from threading import Event, Thread

import httpx
import pytest

from ai.core import llm_chat
from ai.core.chat_transport import MAX_CHAT_BYTES, ModelCallFailure


CHOSEN = {'provider': 'fixture', 'model': 'fixture-chat',
          'base_url': 'http://model.test/v1', 'api_key': 'synthetic-private-model-key'}
PAYLOAD = {'model': 'fixture-chat', 'messages': [{'role': 'user',
                                                  'content': 'synthetic-private-user-prompt'}], 'max_tokens': 32}
ANSWER = {'choices': [{'message': {'role': 'assistant', 'content': 'synthetic-private-model-output'}}],
          'usage': {'prompt_tokens': 11, 'completion_tokens': 7, 'total_tokens': 18}}


def response(value=ANSWER, *, status=200, headers=None):
    raw = value if isinstance(value, bytes) else json.dumps(value).encode()
    return httpx.Response(status, headers=headers or {'Content-Type': 'application/json'}, stream=httpx.ByteStream(raw))


def invoke(server, *, chosen=None, payload=None, timeout=1, meter=False):
    return asyncio.run(llm_chat.call_chat(CHOSEN if chosen is None else chosen, PAYLOAD if payload is None else payload,
                                          timeout, tenant_code='TENANT_A', meter_usage=meter, _transport=httpx.MockTransport(server)))


def test_one_post_exact_model_payload_and_legacy_metering_once(monkeypatch, caplog):
    calls = []
    usage = []
    caplog.set_level(logging.INFO)
    monkeypatch.setattr(llm_chat, 'record_chat_usage', lambda **values: usage.append(values))

    def server(request):
        calls.append(request)
        assert request.url == 'http://model.test/v1/chat/completions' and request.method == 'POST'
        assert request.headers['Authorization'] == 'Bearer ' + CHOSEN['api_key']
        assert request.headers['Accept-Encoding'] == 'identity' and json.loads(request.content) == PAYLOAD
        return response()
    assert invoke(server, meter=True) == ANSWER and len(calls) == 1 and len(usage) == 1
    assert usage[0]['data'] == ANSWER and usage[0]['tenant_code'] == 'TENANT_A'
    assert usage[0].get('success', True) is True
    for secret in (CHOSEN['api_key'], PAYLOAD['messages'][0]['content'], ANSWER['choices'][0]['message']['content']):
        assert secret not in caplog.text


def test_runtime_opt_out_does_not_read_settings_or_write_a_second_usage_ledger(monkeypatch):
    monkeypatch.setattr(llm_chat, 'record_chat_usage', lambda **_: pytest.fail('unexpected legacy meter'))
    monkeypatch.setattr(llm_chat, 'get_settings', lambda: pytest.fail('transport read developer settings'))
    assert invoke(lambda _: response()) == ANSWER


@pytest.mark.parametrize('status,code,retryable', [(400, 'MODEL_REQUEST_REJECTED', False), (401, 'MODEL_AUTHORIZATION_DENIED', False),
                                                   (403, 'MODEL_AUTHORIZATION_DENIED', False), (404,
                                                                                                'MODEL_REQUEST_REJECTED', False), (422, 'MODEL_REQUEST_REJECTED', False),
                                                   (408, 'MODEL_UNAVAILABLE', True), (425, 'MODEL_UNAVAILABLE',
                                                                                      True), (429, 'MODEL_RATE_LIMITED', True),
                                                   (500, 'MODEL_UNAVAILABLE', True), (503, 'MODEL_UNAVAILABLE', True), (302, 'MODEL_PROTOCOL_ERROR', False)])
def test_http_classification_is_stable_no_body_no_hidden_retry_and_metered_once(status, code, retryable, monkeypatch, caplog):
    calls = []
    usage = []
    monkeypatch.setattr(llm_chat, 'record_chat_usage', lambda **values: usage.append(values))
    def server(_): calls.append(1); return response(b'private-provider-echo-of-key-and-prompt', status=status)
    with pytest.raises(ModelCallFailure, match='^' + code + '$') as caught:
        invoke(server, meter=True)
    assert caught.value.retryable is retryable and caught.value.status_code == status and caught.value.attempted
    assert calls == [1] and len(usage) == 1 and usage[0]['success'] is False
    assert 'private-provider' not in str(caught.value) + caplog.text and CHOSEN['api_key'] not in caplog.text


@pytest.mark.parametrize('field,value', [('base_url', 'file:///tmp/model'), ('base_url', 'http://user:pass@model.test/v1'),
                                         ('base_url', 'http://model.test/v1?'), ('base_url',
                                                                                 'http://model.test/v1#x'), ('base_url', 'http://model.test/../v1'),
                                         ('base_url', 'http://model.test/%2e%2e/v1'), ('base_url',
                                                                                       'http://model.test:invalid/'),
                                         ('api_key', ''), ('api_key', 'private\nkey'), ('api_key', '非ASCII'), ('model', 'private\rmodel')])
def test_invalid_host_configuration_fails_before_http_and_is_not_an_http_attempt(field, value, monkeypatch):
    chosen = CHOSEN | {field: value}
    monkeypatch.setattr(llm_chat, 'record_chat_usage', lambda **
                        _: pytest.fail('invalid configuration was metered'))
    with pytest.raises(ModelCallFailure, match='^MODEL_CONFIGURATION_INVALID$') as caught:
        invoke(lambda _: pytest.fail('invalid configured target reached HTTP'), chosen=chosen, meter=True)
    assert not caught.value.attempted and not caught.value.retryable


@pytest.mark.parametrize('timeout', [True, 0, -1, 301, float('nan')])
def test_invalid_deadlines_fail_before_http(timeout):
    with pytest.raises(ModelCallFailure, match='MODEL_CONFIGURATION_INVALID'):
        invoke(lambda _: pytest.fail('invalid deadline'), timeout=timeout)


@pytest.mark.parametrize('payload', [{'model': 'another-model'}, PAYLOAD | {'extra': float('nan')},
                                     PAYLOAD | {'extra': 'x' * (MAX_CHAT_BYTES + 1)
                                                }, PAYLOAD | {'extra': '中' * 100000},
                                     PAYLOAD | {'extra': {1: 'non-string-key'}}, PAYLOAD | {'extra': object()}])
def test_request_model_and_structure_are_bounded_before_any_http(payload):
    with pytest.raises(ModelCallFailure, match='MODEL_REQUEST_INVALID') as caught:
        invoke(lambda _: pytest.fail('invalid request reached HTTP'), payload=payload)
    assert not caught.value.attempted


@pytest.mark.parametrize('raw', [b'[]', b'null', b'{', b'\xff', b'{"x":1,"x":2}', b'{"x":{"y":1,"y":2}}', b'{"x":NaN}'])
def test_malformed_duplicate_or_non_json_responses_are_stable_failures(raw):
    with pytest.raises(ModelCallFailure, match='^MODEL_RESPONSE_INVALID$'):
        invoke(lambda _: response(raw))


@pytest.mark.parametrize('headers', [{'Content-Type': 'text/html'}, [('Content-Type', 'application/json'), ('Content-Type', 'application/json')],
                                     {'Content-Type': 'application/json', 'Content-Encoding': 'gzip'}])
def test_wrong_mime_duplicate_header_and_unbounded_compression_are_not_accepted(headers):
    with pytest.raises(ModelCallFailure, match='MODEL_PROTOCOL_ERROR'):
        invoke(lambda _: response(headers=headers))


def test_large_actual_body_is_rejected_without_trusting_declared_content_length():
    with pytest.raises(ModelCallFailure, match='MODEL_RESPONSE_TOO_LARGE'):
        invoke(lambda _: response(b' ' * (MAX_CHAT_BYTES + 1),
               headers={'Content-Type': 'application/json', 'Content-Length': '1'}))


class SlowBody(httpx.AsyncByteStream):
    async def __aiter__(self):
        yield b'{'
        await asyncio.sleep(.2)
        yield b'}'


def test_deadline_includes_slow_body_even_when_transport_ignores_httpx_timeout():
    with pytest.raises(ModelCallFailure, match='^MODEL_TIMEOUT$') as caught:
        invoke(lambda _: httpx.Response(200, headers={
               'Content-Type': 'application/json'}, stream=SlowBody()), timeout=.05)
    assert caught.value.retryable


@pytest.mark.parametrize('error,code', [(httpx.ConnectError('private upstream endpoint'), 'MODEL_NETWORK_ERROR'),
                                        (httpx.ReadTimeout('private timeout content'), 'MODEL_TIMEOUT'), (RuntimeError('private transport detail'), 'MODEL_TRANSPORT_ERROR')])
def test_transport_exception_text_is_not_the_failure_contract(error, code):
    def broken(_): raise error
    with pytest.raises(ModelCallFailure, match='^' + code + '$'):
        invoke(broken)


def test_legacy_meter_failure_does_not_change_model_result_or_emit_exception_text(monkeypatch, caplog):
    def broken(**_): raise RuntimeError('private meter credential detail')
    monkeypatch.setattr(llm_chat, 'record_chat_usage', broken)
    assert invoke(lambda _: response(), meter=True) == ANSWER
    assert 'MODEL_USAGE_UNAVAILABLE' in caplog.text and 'private meter' not in caplog.text


def test_client_disables_environment_proxy_and_redirects(monkeypatch):
    original = httpx.AsyncClient
    options = []
    def client(**kwargs): options.append(kwargs); return original(**kwargs)
    monkeypatch.setattr(httpx, 'AsyncClient', client)
    invoke(lambda _: response())
    assert len(options) == 1 and options[0]['trust_env'] is False and options[0]['follow_redirects'] is False


@contextmanager
def local_provider(mode, response_body=None, *, status=200):
    received = []
    release = Event()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def do_POST(self):
            raw = self.rfile.read(int(self.headers['Content-Length']))
            received.append((self.path, self.headers['Authorization'], json.loads(raw)))
            body = json.dumps(ANSWER if response_body is None else response_body).encode()
            self.send_response(302 if mode == 'redirect' else status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Connection', 'close')
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
        yield 'http://127.0.0.1:' + str(server.server_port) + '/v1', received
    finally:
        release.set()
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


@pytest.mark.parametrize('mode,expected', [('normal', None), ('redirect', 'MODEL_PROTOCOL_ERROR'), ('slow', 'MODEL_TIMEOUT')])
def test_actual_tcp_provider_body_deadline_and_redirect_boundary_without_environment_proxy(mode, expected, monkeypatch):
    monkeypatch.setenv('HTTP_PROXY', 'http://127.0.0.1:1')
    monkeypatch.setenv('ALL_PROXY', 'http://127.0.0.1:1')
    monkeypatch.setenv('NO_PROXY', '')
    with local_provider(mode) as (base, received):
        async def call(): return await llm_chat.call_chat(
            CHOSEN | {'base_url': base}, PAYLOAD, 1 if mode == 'slow' else 2, meter_usage=False)
        if expected:
            with pytest.raises(ModelCallFailure, match='^' + expected + '$'):
                asyncio.run(call())
        else:
            assert asyncio.run(call()) == ANSWER
        assert len(received) == 1 and received[0] == (
            '/v1/chat/completions', 'Bearer ' + CHOSEN['api_key'], PAYLOAD)
