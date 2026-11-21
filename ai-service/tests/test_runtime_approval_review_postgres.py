"""Signed review requests change only the exact persisted tenant proposal."""
import os
from uuid import uuid4

from fastapi.testclient import TestClient
import pytest

from runtime_api_support import PATH, build_app
from test_approval_lifecycle_postgres import elapsed, wait
from test_runtime_approval_review import post
from test_runtime_store_postgres import store

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
