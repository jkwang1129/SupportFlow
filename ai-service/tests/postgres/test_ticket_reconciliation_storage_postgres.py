"""Exercise the AI audit ledger with the actual restricted runtime role."""
from concurrent.futures import ThreadPoolExecutor
import os
from threading import Barrier
from uuid import uuid4

import psycopg
from psycopg import sql
from psycopg.types.json import Jsonb
import pytest

from ai.core import schema_guard
from ai.runtime.types import Lease
from test_runtime_store_postgres import admin, request, store

pytestmark = pytest.mark.skipif(
    not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'), reason='requires disposable runtime PostgreSQL'
)


def seed(store, tenant='AUDIT_A', session='ticket-audit'):
    source = request(tenant, session)
    run = store.enqueue(source)['run_id']
    lease = Lease.from_run(store.claim_next(tenant, 'audit-fixture-worker'))
    snapshot = uuid4()
    intent = dict(operation='ticket.create', version='ticket-create-v1', resource_ref=source.principal_ref,
                  input_ref=str(snapshot), arguments_sha256='a' * 64)
    step, _ = store.reserve_step(lease, 'ticket-write', 'TOOL', 'WRITE', intent)
    held = store.cancel(tenant, run)
    held_step, = store.steps(tenant, run)
    return dict(tenant_code=tenant, run_id=run, step_no=step['step_no'], observed_fence=held['fence'],
                source_event_id=source.source_event_id, snapshot_id=snapshot, operation='ticket.create',
                schema_version='ticket-create-v1', arguments_sha256=intent['arguments_sha256'], intent_hash=step['intent_hash'],
                ticket_id=9007199254740993 + source.source_event_id.int % 1000000, ticket_no='TK_AUDIT',
                operator_ref='audit-fixture-operator', original_error_code='SIDE_EFFECT_UNKNOWN',
                original_step_result=Jsonb(held_step['result_ref']))


def insert(connection, row, *, deduplicate=False):
    query = sql.SQL('INSERT INTO agent_ticket_reconciliation ({}) VALUES ({})').format(
        sql.SQL(',').join(map(sql.Identifier, row)), sql.SQL(',').join(sql.Placeholder() for _ in row)
    )
    if deduplicate:
        query += sql.SQL(' ON CONFLICT DO NOTHING')
    return connection.execute(query, list(row.values())).rowcount


def test_audit_preserves_int64_and_original_reference_with_no_run_transition(store):
    row = seed(store)
    before = store.get(row['tenant_code'], row['run_id']), store.steps(row['tenant_code'], row['run_id'])
    with store._scope(row['tenant_code']) as connection:
        assert insert(connection, row) == 1
        saved = connection.execute('SELECT * FROM agent_ticket_reconciliation').fetchone()
        assert saved['ticket_id'] == row['ticket_id'] > 2**53
        assert saved['original_step_result'] == row['original_step_result'].obj
        assert saved['confirmed_at'] is not None
    assert (store.get(row['tenant_code'], row['run_id']), store.steps(row['tenant_code'], row['run_id'])) == before


@pytest.mark.parametrize('scope', [None, '', 'PLATFORM', 'AUDIT_B'])
def test_foreign_platform_or_missing_scope_cannot_read_or_insert(store, scope):
    existing = seed(store)
    candidate = seed(store, session='other-audit-run')
    with store._scope('AUDIT_A') as connection:
        insert(connection, existing)
    with psycopg.connect(os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN']) as connection:
        if scope is not None:
            connection.execute("SELECT set_config('app.tenant_code',%s,true)", (scope,))
        assert connection.execute('SELECT * FROM agent_ticket_reconciliation').fetchall() == []
        with pytest.raises(psycopg.errors.InsufficientPrivilege), connection.transaction():
            insert(connection, candidate)


def test_restrictive_policy_survives_additional_permissive_policy(store):
    row = seed(store)
    with store._scope('AUDIT_A') as connection:
        insert(connection, row)
    with admin() as connection:
        connection.execute(
            'CREATE POLICY storage_fixture_allow ON agent_ticket_reconciliation USING(true) WITH CHECK(true)')
    try:
        with store._scope('AUDIT_B') as connection:
            assert connection.execute('SELECT * FROM agent_ticket_reconciliation').fetchall() == []
            with pytest.raises(psycopg.errors.InsufficientPrivilege), connection.transaction():
                insert(connection, row)
    finally:
        with admin() as connection:
            connection.execute('DROP POLICY storage_fixture_allow ON agent_ticket_reconciliation')


@pytest.mark.parametrize('patch', [
    {'operation': 'session.reply'}, {'schema_version': 'reply-v1'}, {'step_no': 0}, {'step_no': 101},
    {'observed_fence': 0}, {'arguments_sha256': 'A' * 64}, {'intent_hash': 'bad'}, {'ticket_id': 0},
    {'ticket_no': '../other'}, {'operator_ref': 'bad operator'}, {'original_error_code': 'SUCCEEDED'},
    {'original_step_result': Jsonb([])}, {'original_step_result': Jsonb({'over': 'x' * 4100})},
])
def test_audit_rejects_out_of_contract_metadata(store, patch):
    row = seed(store)
    with pytest.raises(psycopg.errors.CheckViolation), store._scope('AUDIT_A') as connection:
        insert(connection, row | patch)


def test_foreign_run_and_missing_same_run_step_cannot_be_referenced(store):
    row = seed(store)
    foreign = seed(store, tenant='AUDIT_B')
    with store._scope('AUDIT_A') as connection:
        for patch in ({'run_id': foreign['run_id']}, {'step_no': 2}):
            with pytest.raises(psycopg.errors.ForeignKeyViolation), connection.transaction():
                insert(connection, row | patch)


def test_existing_run_or_ticket_reference_cannot_be_reassigned(store):
    row = seed(store)
    second = seed(store, session='second-ticket-run')
    with store._scope('AUDIT_A') as connection:
        insert(connection, row)
        for changed in (row | {'snapshot_id': uuid4()}, second | {'ticket_id': row['ticket_id']}):
            with pytest.raises(psycopg.errors.UniqueViolation), connection.transaction():
                insert(connection, changed)
        assert connection.execute('SELECT snapshot_id FROM agent_ticket_reconciliation').fetchone()[
            'snapshot_id'] == row['snapshot_id']


def test_runtime_role_has_no_update_delete_truncate_or_ddl(store):
    row = seed(store)
    with store._scope('AUDIT_A') as connection:
        insert(connection, row)
        for query in ('UPDATE agent_ticket_reconciliation SET observed_fence=99', 'DELETE FROM agent_ticket_reconciliation',
                      'TRUNCATE agent_ticket_reconciliation', 'ALTER TABLE agent_ticket_reconciliation NO FORCE ROW LEVEL SECURITY'):
            with pytest.raises(psycopg.errors.InsufficientPrivilege), connection.transaction():
                connection.execute(query)


def test_audit_and_step_changes_roll_back_together(store):
    row = seed(store)
    with pytest.raises(RuntimeError, match='abort audit'), store._scope('AUDIT_A') as connection:
        insert(connection, row)
        connection.execute(
            "UPDATE agent_step SET state='SUCCEEDED' WHERE tenant_code=%s AND run_id=%s", ('AUDIT_A', row['run_id']))
        raise RuntimeError('abort audit')
    with store._scope('AUDIT_A') as connection:
        assert connection.execute('SELECT * FROM agent_ticket_reconciliation').fetchall() == []
    assert store.steps('AUDIT_A', row['run_id'])[0]['state'] == 'UNKNOWN'


def test_runtime_readiness_detects_missing_column_and_mutable_audit(store, monkeypatch):
    monkeypatch.setattr(schema_guard, 'connect', lambda **kwargs: psycopg.connect(
        os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'], row_factory=psycopg.rows.dict_row, **kwargs))
    assert schema_guard.runtime_missing() == {}
    with admin() as connection:
        connection.execute('ALTER TABLE agent_ticket_reconciliation RENAME COLUMN operator_ref TO hidden_operator')
    try:
        assert schema_guard.runtime_missing(
        )['ai_db.agent_ticket_reconciliation.operator_ref'] == schema_guard.TICKET_RECONCILIATION_SOURCE
    finally:
        with admin() as connection:
            connection.execute(
                'ALTER TABLE agent_ticket_reconciliation RENAME COLUMN hidden_operator TO operator_ref')
    with admin() as connection:
        connection.execute('GRANT UPDATE ON agent_ticket_reconciliation TO supportflow_agent_runtime')
    try:
        assert 'ai_db.agent_ticket_reconciliation.runtime_permissions' in schema_guard.runtime_missing()
    finally:
        with admin() as connection:
            connection.execute('REVOKE UPDATE ON agent_ticket_reconciliation FROM supportflow_agent_runtime')
    assert schema_guard.runtime_missing() == {}


def test_eight_concurrent_inserts_leave_one_immutable_audit(store):
    row = seed(store)
    start = Barrier(8)

    def contender(_):
        start.wait(timeout=10)
        with store._scope('AUDIT_A') as connection:
            return insert(connection, row, deduplicate=True)

    with ThreadPoolExecutor(max_workers=8) as pool:
        assert sum(pool.map(contender, range(8))) == 1
    with store._scope('AUDIT_A') as connection:
        assert connection.execute('SELECT count(*) AS n FROM agent_ticket_reconciliation').fetchone()['n'] == 1
