"""PG constraints and runtime grants for the first persistent approval policy."""
from datetime import timedelta
import os
from uuid import uuid4

import psycopg
from psycopg import sql
from psycopg.rows import dict_row
import pytest

from ai.core import schema_guard
from ai.runtime.types import Lease
from test_runtime_store_postgres import admin, request, store

pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'), reason='requires disposable runtime PostgreSQL')


def seed(store, tenant='APPROVAL_A', session='approval-session'):
    source = request(tenant, session)
    run = store.enqueue(source)['run_id']
    return dict(tenant_code=tenant, run_id=run, approval_id=uuid4(), principal_ref=source.principal_ref,
                snapshot_id=uuid4(), tool_name='create_ticket', operation='ticket.create', schema_version='ticket-create-v1',
                purpose='customer-support-ticket', arguments_sha256='a' * 64)


def insert(connection, values):
    query = sql.SQL("INSERT INTO agent_approval ({},expires_at) VALUES ({},clock_timestamp()+interval '60 seconds') RETURNING *").format(
        sql.SQL(',').join(map(sql.Identifier, values)), sql.SQL(',').join(sql.Placeholder() for _ in values))
    return connection.execute(query, list(values.values())).fetchone()


def test_waiting_state_has_no_lease_and_keeps_same_session_order(store):
    row = seed(store)
    tenant, run = row['tenant_code'], row['run_id']
    lease = Lease.from_run(store.claim_next(tenant, 'approval-fixture'))
    store.reserve_step(lease, 'prepare-approval', 'INTERNAL', 'NONE', {'operation': 'approval.prepare'})
    store.complete_step(lease, 'prepare-approval', 'SUCCEEDED', {'outcome': 'prepared'})
    with store._scope(tenant) as connection:
        saved = insert(connection, row)
        assert saved['status'] == 'PENDING' and saved['expires_at'] > saved['requested_at']
        connection.execute("UPDATE agent_run SET state='WAITING_APPROVAL',owner=NULL,lease_until=NULL,fence=fence+1 WHERE run_id=%s", (run,))
    later = store.enqueue(request(tenant, 'approval-session', 2))
    independent = store.enqueue(request(tenant, 'another-session', 1))
    assert store.claim_next(tenant, 'other-worker')['run_id'] == independent['run_id']
    held = store.get(tenant, run)
    assert held['state'] == 'WAITING_APPROVAL' and held['owner'] is None and held['lease_until'] is None
    assert held['step_count'] == 1 and held['retry_count'] == 0 and held['fence'] == lease.fence + 1
    assert store.get(tenant, later['run_id'])['state'] == 'PENDING'


def test_waiting_state_rejects_partial_stale_lease(store):
    row = seed(store)
    with pytest.raises(psycopg.errors.CheckViolation), store._scope(row['tenant_code']) as connection:
        connection.execute("UPDATE agent_run SET state='WAITING_APPROVAL',owner='stale-worker' WHERE run_id=%s", (row['run_id'],))


@pytest.mark.parametrize('patch', [
    {'tool_name': 'other_tool'}, {'operation': 'session.reply'}, {'schema_version': 'reply-v1'},
    {'purpose': 'refund'}, {'arguments_sha256': 'bad'}, {'principal_ref': 'other principal'},
    {'status': 'APPROVED'}, {'status': 'CONSUMED'},
])
def test_invalid_approval_authority_fields_and_incomplete_decisions_are_rejected(store, patch):
    row = seed(store)
    with pytest.raises(psycopg.errors.CheckViolation), store._scope(row['tenant_code']) as connection:
        insert(connection, row | patch)


def test_foreign_run_and_second_approval_for_same_run_are_rejected(store):
    row = seed(store)
    foreign = seed(store, 'APPROVAL_B')
    with store._scope('APPROVAL_A') as connection:
        with pytest.raises(psycopg.errors.ForeignKeyViolation), connection.transaction():
            insert(connection, row | {'run_id': foreign['run_id']})
        insert(connection, row)
        with pytest.raises(psycopg.errors.UniqueViolation), connection.transaction():
            insert(connection, row | {'approval_id': uuid4()})


@pytest.mark.parametrize('scope', ['', 'PLATFORM', 'APPROVAL_B'])
def test_other_scope_cannot_view_or_create_approval(store, scope):
    row = seed(store)
    other = seed(store, session='second-approval-session')
    with store._scope('APPROVAL_A') as connection:
        insert(connection, row)
    with psycopg.connect(os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN']) as connection:
        connection.execute("SELECT set_config('app.tenant_code',%s,true)", (scope,))
        assert connection.execute('SELECT approval_id FROM agent_approval').fetchall() == []
        with pytest.raises(psycopg.errors.InsufficientPrivilege), connection.transaction():
            insert(connection, other)


def test_runtime_can_record_decision_but_cannot_edit_original_terms_or_delete(store):
    row = seed(store)
    with store._scope('APPROVAL_A') as connection:
        saved = insert(connection, row)
        for statement in ("UPDATE agent_approval SET arguments_sha256=repeat('b',64)",
                          "UPDATE agent_approval SET expires_at=clock_timestamp()+interval '5 minutes'",
                          'DELETE FROM agent_approval', 'TRUNCATE agent_approval'):
            with pytest.raises(psycopg.errors.InsufficientPrivilege), connection.transaction():
                connection.execute(statement)
        assert connection.execute("""UPDATE agent_approval SET status='APPROVED',decided_by='reviewer:1',
            decided_at=clock_timestamp() WHERE run_id=%s""", (row['run_id'],)).rowcount == 1
        result = connection.execute('SELECT * FROM agent_approval WHERE run_id=%s', (row['run_id'],)).fetchone()
        assert result['status'] == 'APPROVED' and result['arguments_sha256'] == saved['arguments_sha256']
        with pytest.raises(psycopg.errors.ForeignKeyViolation), connection.transaction():
            connection.execute("""UPDATE agent_approval SET status='CONSUMED',consumed_step_no=1,
                consumed_at=clock_timestamp() WHERE run_id=%s""", (row['run_id'],))


def test_expiry_and_decision_clock_constraints(store):
    row = seed(store)
    with store._scope('APPROVAL_A') as connection:
        saved = insert(connection, row)
        for rejected in (saved['requested_at'] - timedelta(seconds=1), saved['expires_at']):
            with pytest.raises(psycopg.errors.CheckViolation), connection.transaction():
                connection.execute("UPDATE agent_approval SET status='APPROVED',decided_by='reviewer:1',decided_at=%s", (rejected,))


def test_pause_and_approval_row_are_atomic_in_the_database(store):
    row = seed(store)
    with pytest.raises(RuntimeError, match='abort pause'), store._scope('APPROVAL_A') as connection:
        insert(connection, row)
        connection.execute("UPDATE agent_run SET state='WAITING_APPROVAL' WHERE run_id=%s", (row['run_id'],))
        raise RuntimeError('abort pause')
    assert store.get('APPROVAL_A', row['run_id'])['state'] == 'PENDING'
    with store._scope('APPROVAL_A') as connection:
        assert connection.execute('SELECT approval_id FROM agent_approval').fetchall() == []


def test_readiness_rejects_expanded_or_missing_approval_column_grants(store, monkeypatch):
    monkeypatch.setattr(schema_guard, 'connect', lambda **kwargs: psycopg.connect(
        os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'], row_factory=dict_row, **kwargs))
    row = seed(store)
    with store._scope('APPROVAL_A') as connection:
        insert(connection, row)
    key = 'ai_db.agent_approval.runtime_permissions'
    assert schema_guard.runtime_missing() == {}
    for grant, restore in (
        ('GRANT UPDATE(arguments_sha256) ON agent_approval TO supportflow_agent_runtime',
         'REVOKE UPDATE(arguments_sha256) ON agent_approval FROM supportflow_agent_runtime'),
        ('REVOKE UPDATE(status) ON agent_approval FROM supportflow_agent_runtime',
         'GRANT UPDATE(status) ON agent_approval TO supportflow_agent_runtime'),
    ):
        with admin() as connection:
            connection.execute(grant)
        try:
            assert schema_guard.runtime_missing()[key] == schema_guard.APPROVAL_SOURCE
        finally:
            with admin() as connection:
                connection.execute(restore)
    assert schema_guard.runtime_missing() == {}
