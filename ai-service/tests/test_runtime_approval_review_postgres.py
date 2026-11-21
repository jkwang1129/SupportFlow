"""Signed review requests change only the exact persisted tenant proposal."""
from datetime import datetime
import os
from uuid import uuid4

from fastapi.testclient import TestClient
import pytest

from runtime_api_support import PATH, build_app
from test_approval_lifecycle_postgres import CONTENT, TITLE, elapsed, wait
from test_runtime_approval_review import post
from test_runtime_store_postgres import admin, store

pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'), reason='requires disposable runtime PostgreSQL')


def setup(store, monkeypatch):
    source, waiting, approvals, row = wait(store)
    app = build_app(monkeypatch, store=store, approval_enabled=True)
    path = f"{PATH}/{row['run_id']}/review-ticket-approval"
    payload = dict(approval_id=str(row['approval_id']), source_event_id=str(source.source_event_id),
                   arguments_sha256=row['arguments_sha256'], reviewer_ref='staff:123', decision='approve')
    return app, path, payload, source, waiting, approvals, row


def test_wrong_tenant_event_hash_and_approval_cannot_change_new_wait(store, monkeypatch):
    app, path, payload, source, waiting, approvals, row = setup(store, monkeypatch)
    with TestClient(app) as client:
        assert post(client, path, payload, tenant='TENANT_B').status_code == 409
        for change in (dict(approval_id=str(uuid4())), dict(source_event_id=str(uuid4())), dict(arguments_sha256='b'*64)):
            assert post(client, path, payload | change).status_code == 409
    assert store.get(source.tenant, row['run_id']) == waiting and approvals.get(source.tenant, row['run_id']) == row


def test_approval_repeat_keeps_original_staff_and_changed_revocation_is_blocked(store, monkeypatch):
    app, path, payload, source, waiting, approvals, row = setup(store, monkeypatch)
    with TestClient(app) as client:
        assert post(client, path, payload).json() == {'approval_id': str(row['approval_id']), 'status': 'APPROVED'}
        approved = approvals.get(source.tenant, row['run_id'])
        assert post(client, path, payload | {'reviewer_ref': 'staff:124'}).status_code == 200
        assert approvals.get(source.tenant, row['run_id']) == approved and approved['decided_by'] == 'staff:123'
        assert post(client, path, payload | {'decision': 'revoke', 'arguments_sha256': 'b'*64}).status_code == 409
        assert post(client, path, payload | {'decision': 'revoke', 'reviewer_ref': 'staff:124'}).json()['status'] == 'REVOKED'
    final = store.get(source.tenant, row['run_id'])
    assert (final['state'], final['error_code'], final['step_count']) == ('FAILED', 'APPROVAL_REVOKED', waiting['step_count'])
    assert approvals.get(source.tenant, row['run_id'])['closed_by'] == 'staff:124'


def test_denial_never_queues_work_and_repeated_approve_conflicts(store, monkeypatch):
    app, path, payload, source, _, approvals, row = setup(store, monkeypatch)
    with TestClient(app) as client:
        assert post(client, path, payload | {'decision': 'deny'}).json()['status'] == 'DENIED'
        assert post(client, path, payload).status_code == 409
    assert store.claim_next(source.tenant, 'must-not-run') is None
    assert approvals.get(source.tenant, row['run_id'])['decided_by'] == 'staff:123'


@pytest.mark.parametrize('decision', ['approve', 'revoke'])
def test_expired_review_returns_conflict_after_recording_expiry(store, monkeypatch, decision):
    app, path, payload, source, _, approvals, row = setup(store, monkeypatch)
    with TestClient(app) as client:
        if decision == 'revoke':
            assert post(client, path, payload).status_code == 200
        elapsed(row)
        failure = post(client, path, payload | {'decision': decision})
        assert failure.status_code == 409 and failure.json() == {'detail': 'Approval has expired'}
    assert approvals.get(source.tenant, row['run_id'])['status'] == 'EXPIRED'
    assert store.get(source.tenant, row['run_id'])['error_code'] == 'APPROVAL_EXPIRED'


def test_preview_preserves_original_terms_and_never_advances_run(store, monkeypatch):
    app, review, command, source, waiting, approvals, row = setup(store, monkeypatch)
    route = f"{PATH}/{row['run_id']}/ticket-approval-preview"
    ref = {'source_event_id': str(source.source_event_id)}
    with TestClient(app) as client:
        result = post(client, route, ref)
        assert result.status_code == 200 and result.headers['cache-control'] == 'no-store'
        assert result.json() == dict(approval_id=str(row['approval_id']), status='PENDING',
            expires_at=result.json()['expires_at'], arguments_sha256=row['arguments_sha256'], title=TITLE, content=CONTENT)
        assert datetime.fromisoformat(result.json()['expires_at']) == row['expires_at']
        assert store.get(source.tenant, row['run_id']) == waiting
        assert approvals.get(source.tenant, row['run_id']) == row
        assert post(client, review, command).status_code == 200
        assert post(client, route, ref).json()['status'] == 'APPROVED'


def test_wrong_tenant_or_source_cannot_read_terms(store, monkeypatch):
    app, _, _, source, _, _, row = setup(store, monkeypatch)
    route = f"{PATH}/{row['run_id']}/ticket-approval-preview"
    with TestClient(app) as client:
        wrong_tenant = post(client, route, {'source_event_id': str(source.source_event_id)}, tenant='TENANT_B')
        wrong_event = post(client, route, {'source_event_id': str(uuid4())})
    assert wrong_tenant.status_code == wrong_event.status_code == 404
    assert wrong_tenant.json() == wrong_event.json() == {'detail': 'Approval proposal not found'}


@pytest.mark.parametrize('close', ['deny', 'revoke', 'expires'])
def test_closed_or_expired_terms_are_unavailable(store, monkeypatch, close):
    app, review, command, source, _, _, row = setup(store, monkeypatch)
    route = f"{PATH}/{row['run_id']}/ticket-approval-preview"
    with TestClient(app) as client:
        if close == 'revoke':
            assert post(client, review, command).status_code == 200
        if close == 'expires':
            elapsed(row)
        else:
            assert post(client, review, command | {'decision': close}).status_code == 200
        before = store.get(source.tenant, row['run_id'])
        assert post(client, route, {'source_event_id': str(source.source_event_id)}).status_code == 404
    assert store.get(source.tenant, row['run_id']) == before


def test_invalid_checkpoint_digest_never_returns_terms(store, monkeypatch):
    app, _, _, source, _, _, row = setup(store, monkeypatch)
    route = f"{PATH}/{row['run_id']}/ticket-approval-preview"
    with admin() as connection:
        connection.execute('UPDATE agent_payload SET payload_hash=%s WHERE run_id=%s', ('0'*64, row['run_id']))
    with TestClient(app) as client:
        reply = post(client, route, {'source_event_id': str(source.source_event_id)})
    assert reply.status_code == 409 and reply.json() == {'detail': 'Approval proposal is unavailable'}


def test_active_reference_is_only_run_and_source_metadata_for_this_session(store, monkeypatch):
    app, review, command, source, waiting, approvals, row = setup(store, monkeypatch)
    route, ref = f'{PATH}/ticket-approval-active', {'session_no': source.session_no}
    with TestClient(app) as client:
        pending = post(client, route, ref)
        assert pending.status_code == 200 and pending.headers['cache-control'] == 'no-store'
        body = pending.json()
        assert body == dict(run_id=str(row['run_id']), source_event_id=str(source.source_event_id),
                            status='PENDING', expires_at=body['expires_at'])
        assert datetime.fromisoformat(body['expires_at']) == row['expires_at']
        assert store.get(source.tenant, row['run_id']) == waiting and approvals.get(source.tenant, row['run_id']) == row
        assert post(client, route, ref, tenant='TENANT_B').status_code == 404
        assert post(client, route, {'session_no': 'OTHER_SESSION'}).status_code == 404
        assert post(client, review, command).status_code == 200
        assert post(client, route, ref).json()['status'] == 'APPROVED'
        assert post(client, review, command | {'decision': 'revoke'}).status_code == 200
        assert post(client, route, ref).status_code == 404


def test_elapsed_approval_is_hidden_even_before_worker_settles_it(store, monkeypatch):
    app, _, _, source, waiting, approvals, row = setup(store, monkeypatch)
    elapsed(row)
    with TestClient(app) as client:
        reply = post(client, f'{PATH}/ticket-approval-active', {'session_no': source.session_no})
    assert reply.status_code == 404 and reply.json() == {'detail': 'Active approval not found'}
    assert store.get(source.tenant, row['run_id']) == waiting
    assert approvals.get(source.tenant, row['run_id'])['status'] == 'PENDING'
