"""Write authority is separate from source access and old internal credentials."""
import hashlib
import hmac
from uuid import UUID, uuid4

from fastapi.testclient import TestClient
import psycopg
import pytest

from ai.runtime.authority import sign
from ai.runtime.types import ActionDenied
from runtime_api_support import PATH, KEYS, build_app, encode, signed
from test_internal_auth import signed as legacy_signed


def grant():
    return dict(version='action-v1', tenant_code='TENANT_A',
                run_id='00000000-0000-0000-0000-000000000001',
                source_event_id='00000000-0000-0000-0000-000000000002',
                owner='worker-1', fence=3, step_key='reply-1', step_no=2,
                operation='session.reply', snapshot_id='00000000-0000-0000-0000-000000000003',
                arguments_sha256=hashlib.sha256(b'synthetic reply').hexdigest(), expires_at_ms=1700000004900)


def action():
    value = grant()
    return {key: value[key] for key in ('tenant_code', 'source_event_id', 'owner', 'fence',
                                      'snapshot_id', 'arguments_sha256')}


class AuthorityStore:
    def __init__(self):
        self.calls = []
        self.failure = None

    def authorize_reply(self, *args):
        self.calls.append(args)
        if self.failure: raise self.failure
        return grant() if args[0] == 'TENANT_A' and str(args[1]) == grant()['run_id'] else None


@pytest.fixture
def client(monkeypatch):
    store = AuthorityStore()
    with TestClient(build_app(monkeypatch, store=store)) as client:
        client.store = store
        yield client


def send(client, value=None, *, path=None, tenant='TENANT_A', caller='customer'):
    path = path or PATH + '/' + grant()['run_id'] + '/authorize'
    body = encode(action() if value is None else value)
    return client.post(path, content=body, headers=signed('POST', path, body, tenant=tenant, caller=caller))


def test_fixed_permit_canonical_wire_and_all_fields_bound():
    value = grant()
    # Literal protocol fixture, independent of production field iteration.
    canonical = '\n'.join(('supportflow-agent-action:v1', 'action-v1', 'TENANT_A',
                          value['run_id'], value['source_event_id'], 'worker-1', '3', 'reply-1', '2',
                          'session.reply', value['snapshot_id'], value['arguments_sha256'], '1700000004900'))
    key = KEYS['customer']
    signature = hmac.digest(key.encode(), canonical.encode(), 'sha256').hex()
    assert signature == '93e4a92722d1651caebf0786db91cc7e4532660377ea0cdbbc1489a33af9ec18'
    result = sign(value, key)
    assert result == {**value, 'signature': signature} and value == grant()
    for field in value:
        changed = {**value, field: value[field] + 1 if type(value[field]) is int else value[field] + 'x'}
        assert sign(changed, key)['signature'] != signature
    assert sign(value, KEYS['ops'])['signature'] != signature
    for invalid in ({}, {**value, 'unexpected': 'x'}):
        with pytest.raises(ValueError): sign(invalid, key)
    with pytest.raises(ValueError): sign(value, 'short')


def test_authority_is_signed_only_after_persistence_authorizes(client):
    response = send(client)
    assert response.status_code == 200 and response.json() == sign(grant(), KEYS['customer'])
    args = client.store.calls[0]
    assert args == ('TENANT_A', UUID(grant()['run_id']), action()['source_event_id'],
                    'worker-1', 3, action()['snapshot_id'], action()['arguments_sha256'])


@pytest.mark.parametrize('caller', ['gateway', 'ops', 'tenant'])
def test_other_service_roles_cannot_request_authority(client, caller):
    assert send(client, caller=caller).status_code == 403
    assert not client.store.calls


@pytest.mark.parametrize('change', [dict(owner='../worker'), dict(fence=True), dict(fence='3'),
                                  dict(fence=0), dict(fence=9223372036854775808),
                                  dict(operation='ticket.create'), dict(version='reply-v2'),
                                  dict(snapshot_id='ABCDEF00-0000-0000-0000-000000000003'),
                                  dict(arguments_sha256='A' * 64), dict(max_tool_calls=100)])
def test_action_schema_cannot_expand_authority(client, change):
    assert send(client, {**action(), **change}).status_code == 422
    assert not client.store.calls


def test_tenant_missing_run_and_legacy_credential_fail_closed(client):
    assert send(client, {**action(), 'tenant_code': 'TENANT_B'}).status_code == 403
    assert not client.store.calls
    assert send(client, path=PATH + '/' + str(uuid4()) + '/authorize').status_code == 404
    path = PATH + '/' + grant()['run_id'] + '/authorize'
    count = len(client.store.calls)
    assert client.post(path, content=encode(action()), headers=legacy_signed('POST', path)).status_code == 401
    assert len(client.store.calls) == count


@pytest.mark.parametrize('failure,status', [(ActionDenied('sensitive owner'), 409),
                                          (psycopg.OperationalError('sensitive dsn'), 503)])
def test_action_errors_are_stable_and_nonleaking(client, failure, status):
    client.store.failure = failure
    response = send(client)
    assert response.status_code == status and 'sensitive' not in response.text


def test_disabled_authority_never_opens_persistence(monkeypatch):
    from ai.api import runtime
    def unexpected(): raise AssertionError('disabled authority opened persistence')
    monkeypatch.setattr(runtime, 'RuntimeStore', unexpected)
    with TestClient(build_app(monkeypatch, enabled=False)) as client:
        assert send(client).status_code == 503


@pytest.mark.parametrize('tamper', ['body', 'path', 'method', 'tenant'])
def test_authority_request_is_bound_to_wire(client, tamper):
    path = PATH + '/' + grant()['run_id'] + '/authorize'
    body = encode(action())
    values = dict(method='POST', path=path, body=body)
    if tamper == 'body': values['body'] += b' '
    if tamper == 'path': values['path'] = PATH + '/' + str(uuid4()) + '/authorize'
    if tamper == 'method': values['method'] = 'GET'
    if tamper == 'tenant': values['tenant'] = 'TENANT_B'
    headers = signed(**values)
    headers['X-Tenant-Code'] = 'TENANT_A'
    assert client.post(path, content=body, headers=headers).status_code == 401
    assert not client.store.calls
