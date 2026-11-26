"""Durable nested TOOL/MODEL budgets and live knowledge policy on restricted PG."""
from dataclasses import replace
import json
import os

import psycopg
import pytest

from ai.runtime.payloads import encode
from ai.runtime.types import Limits
from ai.runtime.worker import RuntimeWorker, TransientFailure
from test_runtime_store_postgres import acquire, store, request, admin
from test_runtime_embeddings import profile
from test_runtime_embeddings_postgres import next_ready
from test_knowledge_tools import setup, call

pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),
                                reason='requires disposable runtime PostgreSQL')


def runner(store, state, counts, *, model_profile=None, retry=False, outputs=None):
    def handler(ctx):
        _, executor, _, _, _ = setup(context=ctx, state=state, counts=counts, model_profile=model_profile)
        value = executor.execute(ctx, 'knowledge-1', call(**({'doc_ids': []} if state.get('empty') else {})))
        if outputs is not None:
            outputs.append(value)
        if retry:
            raise TransientFailure('AFTER_KNOWLEDGE', delay_seconds=0)
        return {'outcome': 'knowledge-verified'}
    return RuntimeWorker(store, {'runtime-v1': handler}, 'knowledge-tool-worker')


def counts(): return dict(source=0, embedding=0, knowledge=0)


def test_restart_reuses_embedding_but_requeries_offlined_knowledge_with_original_budgets(store):
    row = store.enqueue(request())
    state = {}
    calls = counts()
    outputs = []
    first = runner(store, state, calls, retry=True, outputs=outputs).run_once('TENANT_A')
    assert first['state'] == 'RETRY_WAIT' and (
        first['step_count'], first['tool_calls'], first['model_calls']) == (5, 2, 1)
    state['offline'] = True
    result = runner(store, state, calls, outputs=outputs).run_once('TENANT_A')
    assert result['state'] == 'SUCCEEDED' and (
        result['step_count'], result['tool_calls'], result['model_calls'], result['retry_count']) == (7, 4, 1, 1)
    assert calls == dict(source=2, embedding=1, knowledge=2) and outputs[0]['hits'] and outputs[1]['hits'] == []
    with admin() as db:
        payloads = db.execute('SELECT payload_schema,payload_json FROM agent_payload ORDER BY step_no').fetchall()
        assert len(payloads) == 3 and sum(item['payload_schema'] == 'model-output-v1' for item in payloads) == 1
        assert all('synthetic query' not in item['payload_json'] and profile(
        ).api_key not in item['payload_json'] for item in payloads)
    assert 'synthetic query' not in str(store.steps('TENANT_A', row['run_id']))


def test_revoked_source_after_restart_prevents_cached_vector_or_knowledge_redisclosure(store):
    store.enqueue(request())
    state = {}
    calls = counts()
    assert runner(store, state, calls, retry=True).run_once('TENANT_A')['state'] == 'RETRY_WAIT'
    state['revoked'] = True
    result = runner(store, state, calls).run_once('TENANT_A')
    assert result['state'] == 'FAILED' and result['error_code'] == 'SOURCE_REVOKED'
    assert (result['tool_calls'], result['model_calls']) == (
        3, 1) and calls == dict(source=2, embedding=1, knowledge=1)


@pytest.mark.parametrize('limits,expected', [(Limits(max_model_calls=0), (2, 0)), (Limits(max_tool_calls=1), (1, 0))])
def test_original_model_or_tool_budget_blocks_next_external_request(store, limits, expected):
    store.enqueue(request(), limits)
    calls = counts()
    result = runner(store, {}, calls).run_once('TENANT_A')
    assert result['state'] == 'FAILED' and result['error_code'] == 'BUDGET_EXHAUSTED'
    assert (result['tool_calls'], result['model_calls']
            ) == expected and calls == dict(source=1, embedding=0, knowledge=0)


def test_no_free_live_refresh_after_tool_budget_is_exhausted(store):
    store.enqueue(request(), Limits(max_tool_calls=3))
    state = {}
    calls = counts()
    assert runner(store, state, calls, retry=True).run_once('TENANT_A')['state'] == 'RETRY_WAIT'
    result = runner(store, state, calls).run_once('TENANT_A')
    assert result['state'] == 'FAILED' and result['error_code'] == 'BUDGET_EXHAUSTED'
    assert (result['tool_calls'], result['model_calls']) == (
        3, 1) and calls == dict(source=2, embedding=1, knowledge=1)


def test_nested_embedding_waits_for_tenant_model_quota_then_rechecks_source(store):
    with admin() as db:
        db.execute("INSERT INTO agent_model_quota(tenant_code,max_active_steps) VALUES ('TENANT_A',1)")
    store.enqueue(request(session='holding-chat'))
    holder = acquire(store)
    store.reserve_step(holder, 'model', 'MODEL', 'NONE', {'operation': 'model.chat'})
    work = store.enqueue(request(session='knowledge'), Limits(max_retries=0))
    calls = counts()
    executor = runner(store, {}, calls)
    deferred = executor.run_once('TENANT_A')
    assert deferred['run_id'] == work['run_id']
    assert (deferred['state'], deferred['error_code']) == ('RETRY_WAIT', 'MODEL_CAPACITY_BUSY')
    assert (deferred['model_calls'], deferred['retry_count'], deferred['tool_calls']) == (0, 0, 2)
    assert calls == dict(source=1, embedding=0, knowledge=0)

    store.complete_step(holder, 'model', 'SUCCEEDED', {'outcome': 'done'})
    store.finish(holder, {'outcome': 'done'})
    resumed = next_ready(executor)
    assert (resumed['state'], resumed['model_calls'], resumed['retry_count'],
            resumed['tool_calls']) == ('SUCCEEDED', 1, 0, 4)
    assert calls == dict(source=2, embedding=1, knowledge=1)


def test_nested_embedding_obeys_daily_token_budget_before_provider_or_retrieval(store):
    with admin() as db:
        db.execute('''INSERT INTO agent_model_quota(tenant_code,max_active_steps,daily_token_limit,tokens_per_attempt)
            VALUES ('TENANT_A',8,20,20)''')
    store.enqueue(request(session='charging-chat'))
    holder = acquire(store)
    store.reserve_step(holder, 'model', 'MODEL', 'NONE', {'operation': 'model.chat'})
    store.complete_step(holder, 'model', 'UNKNOWN', {'error_code': 'CALL_FAILED'})
    store.fail(holder, 'MODEL_TIMEOUT')

    work = store.enqueue(request(session='knowledge'))
    calls = counts()
    rejected = runner(store, {}, calls).run_once('TENANT_A')
    assert rejected['run_id'] == work['run_id']
    assert (rejected['state'], rejected['error_code']) == ('FAILED', 'MODEL_TOKEN_QUOTA_EXCEEDED')
    assert (rejected['model_calls'], rejected['retry_count']) == (0, 0)
    assert calls == dict(source=1, embedding=0, knowledge=0)


def test_empty_scope_stores_explicit_empty_result_without_model_or_knowledge_http(store):
    store.enqueue(request())
    calls = counts()
    values = []
    result = runner(store, dict(empty=True), calls, outputs=values).run_once('TENANT_A')
    assert result['state'] == 'SUCCEEDED' and (
        result['step_count'], result['tool_calls'], result['model_calls']) == (4, 2, 0)
    assert calls == dict(source=1, embedding=0, knowledge=0) and values[0]['mode'] == 'empty-scope'
    with admin() as db:
        rows = db.execute('SELECT payload_schema,payload_json FROM agent_payload').fetchall()
        assert len(rows) == 1 and rows[0]['payload_schema'] == 'tool-output-v1'
        assert json.loads(rows[0]['payload_json'])['resource_version'] is None


def test_cancellation_during_embedding_keeps_reservations_and_discards_late_vector(store):
    row = store.enqueue(request())
    calls = counts()
    state = dict(embedding_hook=lambda: store.cancel('TENANT_A', row['run_id']))
    result = runner(store, state, calls).run_once('TENANT_A')
    assert result['state'] == 'CANCELLED' and (result['tool_calls'], result['model_calls']) == (2, 1)
    assert calls == dict(source=1, embedding=1, knowledge=0)
    with admin() as db:
        assert db.execute('SELECT count(*) AS n FROM agent_payload').fetchone()['n'] == 0


@pytest.mark.parametrize('budget,expected', [(1, 'FAILED'), (2, 'SUCCEEDED')])
def test_model_503_retry_uses_shared_retry_and_model_budgets_without_fallback(store, budget, expected):
    store.enqueue(request(), Limits(max_model_calls=budget))
    state = dict(model_status=503)
    calls = counts()
    assert runner(store, state, calls).run_once('TENANT_A')['state'] == 'RETRY_WAIT'
    state['model_status'] = 200
    result = next_ready(runner(store, state, calls))
    assert result['state'] == expected and (
        result['tool_calls'], result['model_calls'], result['retry_count']) == (4, budget, 1)
    assert calls == dict(source=2, embedding=budget, knowledge=1 if budget == 2 else 0)
    if budget == 1:
        assert result['error_code'] == 'BUDGET_EXHAUSTED'


def test_profile_change_after_restart_is_rejected_before_source_and_model_http(store):
    store.enqueue(request())
    state = {}
    calls = counts()
    assert runner(store, state, calls, retry=True).run_once('TENANT_A')['state'] == 'RETRY_WAIT'
    result = runner(store, state, calls, model_profile=profile(model='other-embedding')).run_once('TENANT_A')
    assert result['state'] == 'FAILED' and result['error_code'] == 'KNOWLEDGE_POLICY_CHANGED'
    assert calls == dict(source=1, embedding=1, knowledge=1) and result['model_calls'] == 1


def test_key_rotation_with_same_profile_reuses_vector_but_still_refreshes_knowledge(store):
    store.enqueue(request())
    state = {}
    calls = counts()
    assert runner(store, state, calls, retry=True).run_once('TENANT_A')['state'] == 'RETRY_WAIT'
    result = runner(store, state, calls, model_profile=replace(
        profile(), api_key='rotated-synthetic-embedding-secret')).run_once('TENANT_A')
    assert result['state'] == 'SUCCEEDED' and (result['tool_calls'], result['model_calls']) == (4, 1)
    assert calls == dict(source=2, embedding=1, knowledge=2)


def test_corrupt_vector_checkpoint_cannot_be_used_to_query_current_knowledge(store):
    store.enqueue(request())
    state = {}
    calls = counts()
    assert runner(store, state, calls, retry=True).run_once('TENANT_A')['state'] == 'RETRY_WAIT'
    with admin() as db:
        row = db.execute(
            "SELECT payload_id,payload_json FROM agent_payload WHERE payload_schema='model-output-v1'").fetchone()
        value = json.loads(row['payload_json'])
        value['vector'] = [0.0] * 1024
        raw, digest = encode('model-output-v1', value)
        db.execute('UPDATE agent_payload SET payload_json=%s,payload_hash=%s WHERE payload_id=%s',
                   (raw, digest, row['payload_id']))
    result = runner(store, state, calls).run_once('TENANT_A')
    assert result['state'] == 'FAILED' and result['error_code'] == 'EMBEDDING_CHECKPOINT_INVALID'
    assert calls == dict(source=2, embedding=1, knowledge=1) and result['model_calls'] == 1


def test_lost_committed_read_ack_does_not_authorize_reuse_of_old_knowledge_body(store):
    store.enqueue(request())
    state = {}
    calls = counts()
    values = []

    class LostAck:
        once = True
        def __getattr__(self, name): return getattr(store, name)

        def complete_payload_step(self, lease, key, schema, output):
            value = store.complete_payload_step(lease, key, schema, output)
            if schema == 'tool-output-v1' and self.once:
                self.once = False
                raise psycopg.OperationalError('synthetic lost knowledge read ack')
            return value
    assert runner(LostAck(), state, calls).run_once('TENANT_A')['state'] == 'RETRY_WAIT'
    state['offline'] = True
    result = runner(store, state, calls, outputs=values).run_once('TENANT_A')
    assert result['state'] == 'SUCCEEDED' and (result['tool_calls'], result['model_calls']) == (4, 1)
    assert calls == dict(source=2, embedding=1, knowledge=2) and values[0]['hits'] == []
