"""Ticket permission is opt-in and cannot be reinterpreted as reply authority."""
import hmac

from fastapi.testclient import TestClient
import psycopg
import pytest

from ai.config import Settings
from ai.runtime.authority import sign
from ai.runtime.types import ActionDenied
from runtime_api_support import build_app, KEYS
from test_runtime_authority import AuthorityStore, action, grant, send


def ticket_action(): return action() | dict(operation='ticket.create', version='ticket-create-v1')


def ticket_grant(): return grant() | dict(operation='ticket.create', step_key='ticket-1')


class TicketStore(AuthorityStore):
    def __init__(self): super().__init__(); self.ticket_calls = []

    def authorize_ticket_create(self, *args, require_approval=False):
        self.ticket_calls.append(args)
        if self.failure:
            raise self.failure
        return ticket_grant() | dict(version='action-v2' if require_approval else 'action-v1')


def test_notice_authority_is_explicit_and_only_valid_for_ticket_creation(monkeypatch):
    store = TicketStore()
    with TestClient(build_app(monkeypatch, store=store, ticket_enabled=True)) as client:
        result = send(client, ticket_action() | dict(require_approval=True))
        assert result.status_code == 200
        assert result.json() == sign(ticket_grant() | dict(version='action-v2'), KEYS['customer'])
        assert send(client, action() | dict(require_approval=True)).status_code == 422
        assert send(client, ticket_action() | dict(require_approval='true')).status_code == 422
    assert len(store.ticket_calls) == 1 and not store.calls


def test_ticket_authority_is_disabled_by_default_before_any_store_query(monkeypatch):
    monkeypatch.delenv('SUPPORTFLOW_AI_RUNTIME_TICKET_ACTIONS_ENABLED', raising=False)
    assert Settings(_env_file=None).runtime_ticket_actions_enabled is False
    store = TicketStore()
    with TestClient(build_app(monkeypatch, store=store)) as client:
        assert send(client, ticket_action()).status_code == 503
    assert not store.calls and not store.ticket_calls


def test_explicit_ticket_flag_dispatches_only_the_narrow_ticket_authorizer(monkeypatch):
    store = TicketStore()
    with TestClient(build_app(monkeypatch, store=store, ticket_enabled=True)) as client:
        result = send(client, ticket_action())
        assert result.status_code == 200 and result.json() == sign(ticket_grant(), KEYS['customer'])
        assert send(client, action()).json() == sign(grant(), KEYS['customer'])
    assert len(store.ticket_calls) == len(store.calls) == 1 and len(store.ticket_calls[0]) == 7


@pytest.mark.parametrize('change', [dict(operation='ticket.create'), dict(version='ticket-create-v1'),
                                    dict(operation='refund.issue', version='ticket-create-v1'), dict(
                                        operation='ticket.create', version='ticket-create-v2'),
                                    dict(operation='ticket.create', version='ticket-create-v1', scopes=['admin'])])
def test_cross_pair_unknown_operation_version_and_scope_override_are_rejected(monkeypatch, change):
    store = TicketStore()
    with TestClient(build_app(monkeypatch, store=store, ticket_enabled=True)) as client:
        assert send(client, action() | change).status_code == 422
    assert not store.calls and not store.ticket_calls


@pytest.mark.parametrize('failure,status', [(ActionDenied('PRIVATE SOURCE'), 409), (psycopg.OperationalError('PRIVATE DSN'), 503)])
def test_ticket_authority_errors_do_not_fallback_to_reply_or_leak_details(monkeypatch, failure, status):
    store = TicketStore()
    store.failure = failure
    with TestClient(build_app(monkeypatch, store=store, ticket_enabled=True)) as client:
        result = send(client, ticket_action())
        assert result.status_code == status and 'PRIVATE' not in result.text
    assert len(store.ticket_calls) == 1 and not store.calls


@pytest.mark.parametrize('caller', ['gateway', 'ops', 'tenant'])
def test_other_services_cannot_use_enabled_ticket_authority(monkeypatch, caller):
    store = TicketStore()
    with TestClient(build_app(monkeypatch, store=store, ticket_enabled=True)) as client:
        assert send(client, ticket_action(), caller=caller).status_code == 403
    assert not store.calls and not store.ticket_calls


def test_ticket_signature_literal_fields_preserve_operation_domain():
    value = ticket_grant()
    canonical = '\n'.join(('supportflow-agent-action:v1', 'action-v1', 'TENANT_A', value['run_id'], value['source_event_id'],
                           'worker-1', '3', 'ticket-1', '2', 'ticket.create', value['snapshot_id'], value['arguments_sha256'], '1700000004900'))
    digest = hmac.digest(KEYS['customer'].encode(), canonical.encode(), 'sha256').hex()
    assert sign(value, KEYS['customer'])['signature'] == digest
    assert digest != sign(grant(), KEYS['customer'])['signature']
