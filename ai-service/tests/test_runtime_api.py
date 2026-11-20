import time
from uuid import uuid4

from fastapi.testclient import TestClient
import psycopg
import pytest

from ai.api import runtime
from ai.runtime.types import QueueFull, RuntimeConflict
from runtime_api_support import PATH, build_app, encode, event, signed, KEYS
from test_internal_auth import signed as old_signature


class FakeStore:
    def __init__(self):
        self.calls = []
        self.row = dict(tenant_code='TENANT_A', run_id=uuid4(), source_event_id=uuid4(), state='PENDING',
                        attempt=0, step_count=0, model_calls=0, tool_calls=0, retry_count=0, error_code=None,
                        owner='private-owner', result_ref={'input_ref': 'private-ref'})

    def enqueue(self, value, limits):
        self.calls.append((value, limits))
        return self.row

    def get(self, tenant, run_id):
        self.calls.append((tenant, run_id))
        return self.row if tenant == 'TENANT_A' and run_id == self.row['run_id'] else None

    def cancel(self, tenant, run_id):
        self.row['state'] = 'CANCELLED'
        return self.get(tenant, run_id)


@pytest.fixture
def client(monkeypatch):
    store = FakeStore()
    with TestClient(build_app(monkeypatch, store=store)) as client:
        client.store = store
        yield client


def test_authenticated_reference_admission_status_and_cancel(client):
    body = encode(event())
    response = client.post(PATH, content=body, headers=signed('POST', body=body))
    assert response.status_code == 202 and response.json()['state'] == 'PENDING'
    value, limits = client.store.calls[0]
    assert value.tenant == 'TENANT_A' and limits == runtime.V1_LIMITS
    assert 'private' not in response.text and 'owner' not in response.text and 'result_ref' not in response.text
    path = PATH + '/' + response.json()['run_id']
    assert client.get(path, headers=signed('GET', path)).status_code == 200
    assert client.get(path, headers=signed('GET', path, tenant='TENANT_B')).status_code == 404
    assert client.post(path + '/cancel', content=b'{}', headers=signed('POST', path + '/cancel', b'{}')).json()['state'] == 'CANCELLED'


def test_fixed_wire_fixture_matches_java_signer_and_is_accepted(client, monkeypatch):
    from ai.core import runtime_auth
    body = encode(event(source_event_id='00000000-0000-0000-0000-000000000001'))
    expected = 'runtime:customer:1700000020:232f1a0e53d8be124043925590d6de8265117cb97939ccd5a5eb97baae9a3280'
    headers = signed('POST', body=body, expires=1700000020)
    assert headers['X-SupportFlow-AI-Auth'] == expected
    monkeypatch.setattr(runtime_auth.time, 'time', lambda: 1700000000)
    assert client.post(PATH, content=body, headers=headers).status_code == 202


def test_disabled_receiver_does_not_open_a_runtime_connection(monkeypatch):
    def unexpected(*args, **kwargs):
        raise AssertionError('Disabled receiver cannot construct persistence')
    monkeypatch.setattr(runtime, 'RuntimeStore', unexpected)
    body = encode(event())
    with TestClient(build_app(monkeypatch, enabled=False)) as client:
        response = client.post(PATH, content=body, headers=signed('POST', body=body))
    assert response.status_code == 503 and response.json() == {'detail': 'AI 服务暂不可用'}


def test_v1_cannot_authorize_runtime_and_runtime_cannot_authorize_v1(client):
    body = encode(event())
    assert client.post(PATH, content=body, headers=old_signature('POST', PATH)).status_code == 401
    path = '/api/ai/v1/agent/brain'
    assert client.post(path, content=b'{}', headers=signed('POST', path, b'{}')).status_code == 401
    assert not client.store.calls


@pytest.mark.parametrize('tamper', ['body', 'method', 'path', 'query', 'tenant', 'key', 'expired', 'far-future'])
def test_signature_binds_exact_wire_request_and_expiry(client, tamper):
    body = encode(event())
    args = dict(method='POST', path=PATH, body=body)
    if tamper == 'body': args['body'] = body + b' '
    if tamper == 'method': args['method'] = 'GET'
    if tamper == 'path': args['path'] = PATH + '/other'
    if tamper == 'query': args['path'] += '?extra=1'
    if tamper == 'tenant': args['tenant'] = 'TENANT_B'
    if tamper == 'key': args['key'] = KEYS['gateway']
    if tamper == 'expired': args['expires'] = int(time.time()) - 1
    if tamper == 'far-future': args['expires'] = int(time.time()) + 600
    headers = signed(**args)
    if tamper == 'tenant': headers['X-Tenant-Code'] = 'TENANT_A'
    assert client.post(PATH, content=body, headers=headers).status_code == 401
    assert not client.store.calls


@pytest.mark.parametrize('caller,tenant', [('gateway', 'TENANT_A'), ('ops', 'TENANT_A'),
                                         ('tenant', 'TENANT_A'), ('customer', 'PLATFORM'),
                                         ('customer', 'platform'), ('customer', ''),
                                         ('customer', 'x' * 17)])
def test_caller_and_business_tenant_boundaries(client, caller, tenant):
    body = encode(event())
    assert client.post(PATH, content=body, headers=signed('POST', body=body, caller=caller, tenant=tenant)).status_code == 403
    assert not client.store.calls


def test_duplicate_headers_and_json_fields_are_rejected(client):
    body = encode(event())
    headers = list(signed('POST', body=body).items())
    for duplicate in [('X-Tenant-Code', 'TENANT_A'), ('X-SupportFlow-AI-Auth', headers[0][1])]:
        assert client.post(PATH, content=body, headers=headers + [duplicate]).status_code == 401
    assert client.post(PATH, content=body, headers=headers + [('Content-Type', 'text/plain')]).status_code == 415
    duplicate_json = b'{"tenant_code":"TENANT_B","tenant_code":"TENANT_A"}'
    assert client.post(PATH, content=duplicate_json, headers=signed('POST', body=duplicate_json)).status_code == 400
    assert not client.store.calls


def test_query_encoded_path_get_body_and_foreign_payload_fail_closed(client):
    body = encode(event())
    path = PATH + '?tenant_code=TENANT_A'
    assert client.post(path, content=body, headers=signed('POST', path, body)).status_code == 403
    # Signing decoded path cannot authorize a different raw URL encoding.
    assert client.post(PATH.replace('runs', '%72uns'), content=body, headers=signed('POST', body=body)).status_code == 401
    path = PATH + '/' + str(uuid4())
    assert client.request('GET', path, content=b'{}', headers=signed('GET', path, b'{}')).status_code == 400
    foreign = encode({**event(), 'tenant_code': 'TENANT_B'})
    assert client.post(PATH, content=foreign, headers=signed('POST', body=foreign)).status_code == 403
    assert not client.store.calls


@pytest.mark.parametrize('content,expected', [(b'[]', 400), (b'not-json', 400), (b'\xff', 400),
                                            (b'{}', 422), (b' ' * 8193, 413)])
def test_invalid_and_excessive_bodies_do_not_reach_persistence(client, content, expected):
    assert client.post(PATH, content=content, headers=signed('POST', body=content)).status_code == expected
    assert not client.store.calls


def test_streamed_size_and_content_type_are_enforced(client):
    content = b' ' * 8193
    assert client.post(PATH, content=(content[i:i+1024] for i in range(0, len(content), 1024)),
                       headers=signed('POST', body=content)).status_code == 413
    body = encode(event())
    headers = signed('POST', body=body)
    headers['Content-Type'] = 'text/plain'
    assert client.post(PATH, content=body, headers=headers).status_code == 415
    assert not client.store.calls


@pytest.mark.parametrize('changes', [{'max_model_calls': 100}, {'principal_ref': 'admin:1'},
                                    {'access_token': 'synthetic-credential'}, {'message_id': True},
                                    {'event_seq': '1'}, {'session_no': '../secret'}, {'runtime_version': 'future-v99'},
                                    {'source_event_id': 'ABCDEF00-0000-0000-0000-000000000000'}])
def test_payload_schema_cannot_inject_secrets_authority_or_budgets(client, changes):
    body = encode({**event(), **changes})
    response = client.post(PATH, content=body, headers=signed('POST', body=body))
    assert response.status_code == 422
    assert response.json() == {'detail': 'AI 请求参数格式不正确'}
    assert not client.store.calls


@pytest.mark.parametrize('failure,expected', [(QueueFull('sensitive'), 429), (RuntimeConflict('sensitive'), 409),
                                            (psycopg.OperationalError('sensitive-dsn'), 503)])
def test_storage_errors_map_to_bounded_nonleaking_protocol(client, monkeypatch, failure, expected):
    def fail(*args): raise failure
    monkeypatch.setattr(client.store, 'enqueue', fail)
    body = encode(event())
    response = client.post(PATH, content=body, headers=signed('POST', body=body))
    assert response.status_code == expected and 'sensitive' not in response.text
    if expected == 429: assert response.headers['Retry-After'] == '1'
