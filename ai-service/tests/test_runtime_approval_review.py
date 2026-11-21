"""Signed approval decisions at the AI service boundary."""
from types import SimpleNamespace
from uuid import uuid4

from fastapi.testclient import TestClient
import pytest

from ai.api import runtime
from runtime_api_support import PATH, build_app, encode, signed


def body(**changes):
    return dict(approval_id=str(uuid4()), source_event_id=str(uuid4()), arguments_sha256='a'*64,
                reviewer_ref='staff:123', decision='approve') | changes


def path():
    return f'{PATH}/{uuid4()}/review-ticket-approval'


def post(client, route, value, **signature):
    raw = encode(value)
    return client.post(route, content=raw, headers=signed('POST', route, raw, **signature))


def test_disabled_review_is_separate_from_enabled_runtime(monkeypatch):
    app = build_app(monkeypatch, store=object())
    with TestClient(app) as client:
        assert post(client, path(), body()).status_code == 503


@pytest.mark.parametrize('caller', ['gateway', 'ops', 'tenant'])
def test_other_signed_service_identities_cannot_decide(monkeypatch, caller):
    app = build_app(monkeypatch, store=object(), approval_enabled=True)
    with TestClient(app) as client:
        assert post(client, path(), body(), caller=caller).status_code == 403


@pytest.mark.parametrize('decision', ['approve', 'deny', 'revoke'])
def test_signed_review_uses_tenant_event_and_exact_seen_digest(monkeypatch, decision):
    observed = []

    class Approvals:
        def __init__(self, store):
            assert store is marker

        def decide(self, tenant, run_id, approval_id, reviewer, *, approve, expected):
            observed.append((tenant, run_id, approval_id, reviewer, approve, expected))
            return dict(status='APPROVED' if approve else 'DENIED', approval_id=approval_id, internal_payload='private')

        def revoke(self, tenant, run_id, approval_id, reviewer, *, expected):
            observed.append((tenant, run_id, approval_id, reviewer, None, expected))
            return dict(status='REVOKED', approval_id=approval_id, internal_payload='private')

    from uuid import UUID
    marker = object()
    monkeypatch.setattr(runtime, 'TicketApprovals', Approvals)
    app = build_app(monkeypatch, store=marker, approval_enabled=True)
    route, value = path(), body(decision=decision)
    with TestClient(app) as client:
        result = post(client, route, value, tenant='TENANT_B')
    assert result.status_code == 200 and set(result.json()) == {'approval_id', 'status'}
    choice = None if decision == 'revoke' else decision == 'approve'
    assert observed == [('TENANT_B', UUID(route.split('/')[-2]), UUID(value['approval_id']), 'staff:123', choice,
                         (UUID(value['source_event_id']), value['arguments_sha256']))]


@pytest.mark.parametrize('change', [dict(reviewer_ref='model:123'), dict(reviewer_ref='staff:0'),
    dict(approval_id='not-a-uuid'), dict(source_event_id='ABCDEF00-0000-0000-0000-000000000003'),
    dict(arguments_sha256='A'*64), dict(decision='consume'), dict(owner='forged')])
def test_invalid_authority_or_unregistered_fields_never_reach_approval_store(monkeypatch, change):
    monkeypatch.setattr(runtime, 'TicketApprovals', lambda _: pytest.fail('Invalid command reached approval'))
    app = build_app(monkeypatch, store=SimpleNamespace(), approval_enabled=True)
    with TestClient(app) as client:
        assert post(client, path(), body(**change)).status_code == 422


def test_bad_signature_and_duplicate_body_are_rejected_before_business(monkeypatch):
    monkeypatch.setattr(runtime, 'TicketApprovals', lambda _: pytest.fail('Bad signed body reached approval'))
    app = build_app(monkeypatch, store=object(), approval_enabled=True)
    route = path()
    raw = encode(body())
    duplicate = raw[:-1]+b',"decision":"deny"}'
    with TestClient(app) as client:
        assert client.post(route, content=raw, headers={'Content-Type': 'application/json'}).status_code == 401
        assert client.post(route, content=duplicate, headers=signed('POST', route, raw)).status_code == 401
        assert client.post(route, content=duplicate, headers=signed('POST', route, duplicate)).status_code == 400


@pytest.mark.parametrize('caller, enabled, status', [('gateway', True, 403), ('ops', True, 403),
    ('tenant', True, 403), ('customer', False, 503)])
def test_preview_requires_customer_identity_and_review_flag(monkeypatch, caller, enabled, status):
    monkeypatch.setattr(runtime, 'TicketApprovals', lambda _: pytest.fail('Disallowed preview reached storage'))
    app = build_app(monkeypatch, store=object(), approval_enabled=enabled)
    with TestClient(app) as client:
        route = f'{PATH}/{uuid4()}/ticket-approval-preview'
        assert post(client, route, {'source_event_id': str(uuid4())}, caller=caller).status_code == status


@pytest.mark.parametrize('value', [{'source_event_id': 'bad'}, {'source_event_id': str(uuid4()), 'title': 'new'},
                                 {'source_event_id': str(uuid4()), 'payload_ref': 'untrusted'}])
def test_preview_input_only_accepts_canonical_source_reference(monkeypatch, value):
    monkeypatch.setattr(runtime, 'TicketApprovals', lambda _: pytest.fail('Invalid preview reached storage'))
    app = build_app(monkeypatch, store=object(), approval_enabled=True)
    with TestClient(app) as client:
        route = f'{PATH}/{uuid4()}/ticket-approval-preview'
        assert post(client, route, value).status_code == 422


@pytest.mark.parametrize('caller, enabled, value, status', [
    ('gateway', True, {'session_no': 'SESSION_1'}, 403),
    ('ops', True, {'session_no': 'SESSION_1'}, 403),
    ('tenant', True, {'session_no': 'SESSION_1'}, 403),
    ('customer', False, {'session_no': 'SESSION_1'}, 503),
    ('customer', True, {'session_no': '../invalid'}, 422),
    ('customer', True, {'session_no': 'SESSION_1', 'limit': 100}, 422),
])
def test_active_reference_requires_signed_customer_and_only_one_session(monkeypatch, caller, enabled, value, status):
    monkeypatch.setattr(runtime, 'TicketApprovals', lambda _: pytest.fail('Forbidden approval discovery reached storage'))
    app = build_app(monkeypatch, store=object(), approval_enabled=enabled)
    with TestClient(app) as client:
        assert post(client, f'{PATH}/ticket-approval-active', value, caller=caller).status_code == status
