"""Check the daily accounting boundary with restricted PostgreSQL identities."""
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
import os
from threading import Barrier, Event

import httpx
import psycopg
from psycopg.rows import dict_row
import pytest

from ai.core import schema_guard
from ai.runtime.models import RuntimeChatClient
from ai.runtime.worker import RuntimeWorker
from test_chat_transport import response
from test_runtime_models import MESSAGES, answer, profile
from test_runtime_store_postgres import admin, request, store


pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'), reason='requires disposable runtime PostgreSQL')


def configure(tenant, limit, allowance):
    with admin() as connection:
        connection.execute('''INSERT INTO agent_model_quota(tenant_code,max_active_steps,daily_token_limit,tokens_per_attempt)
            VALUES(%s,8,%s,%s) ON CONFLICT (tenant_code) DO UPDATE SET
            daily_token_limit=EXCLUDED.daily_token_limit,tokens_per_attempt=EXCLUDED.tokens_per_attempt''',
            (tenant, limit, allowance))


def worker(store, provider, owner='token-worker', *, tenant='TENANT_A', barrier=None):
    model = RuntimeChatClient(profile(tenant=tenant), transport=httpx.MockTransport(provider))

    def execute(context):
        if barrier is not None:
            barrier.wait(8)
        model.complete(context, 'model', MESSAGES)
        return {'outcome': 'done'}

    return RuntimeWorker(store, {'runtime-v1': execute}, owner, tenant=tenant)


def enqueue(store, session, tenant='TENANT_A'):
    return store.enqueue(request(tenant, session=session))['run_id']


def usage(tokens_in, tokens_out):
    output = answer()
    output['usage'] = {'prompt_tokens': tokens_in, 'completion_tokens': tokens_out}
    return response(output)


def test_daily_allowance_is_reserved_across_workers_and_settled_by_durable_usage(store):
    configure('TENANT_A', 16, 12)
    configure('TENANT_B', 12, 12)
    contenders = {enqueue(store, 'first'), enqueue(store, 'second')}
    other = enqueue(store, 'other', 'TENANT_B')
    barrier, entered, release = Barrier(2), Event(), Event()
    calls = []

    def hold(_):
        calls.append('A')
        entered.set()
        assert release.wait(15)
        return usage(3, 1)

    with ThreadPoolExecutor(2) as pool:
        futures = [pool.submit(worker(store, hold, owner, barrier=barrier).run_once, 'TENANT_A')
                   for owner in ('token-a1', 'token-a2')]
        try:
            assert entered.wait(8)
            done, pending = wait(futures, timeout=8, return_when=FIRST_COMPLETED)
            assert len(done) == len(pending) == 1
            denied = done.pop().result()
            assert denied['run_id'] in contenders and denied['state'] == 'FAILED'
            assert denied['error_code'] == 'MODEL_TOKEN_QUOTA_EXCEEDED'
            assert (denied['model_calls'], denied['retry_count'], denied['step_count']) == (0, 0, 0)
            assert calls == ['A']
            independently = worker(store, lambda _: usage(5, 2), 'token-b', tenant='TENANT_B').run_once('TENANT_B')
            assert (independently['run_id'], independently['state']) == (other, 'SUCCEEDED')
        finally:
            release.set()
        settled = pending.pop().result(timeout=10)
    assert settled['run_id'] in contenders - {denied['run_id']} and settled['state'] == 'SUCCEEDED'

    with admin() as connection:
        connection.execute('DELETE FROM agent_payload WHERE run_id=%s', (settled['run_id'],))
    follow_up = enqueue(store, 'additional')
    accepted = worker(store, lambda _: usage(9, 3)).run_once('TENANT_A')
    assert (accepted['run_id'], accepted['state']) == (follow_up, 'SUCCEEDED')
    through_limit = enqueue(store, 'over-limit')
    exhausted = worker(store, lambda _: pytest.fail('exhausted quota reached provider')).run_once('TENANT_A')
    assert (exhausted['run_id'], exhausted['error_code']) == (through_limit, 'MODEL_TOKEN_QUOTA_EXCEEDED')
    with admin() as connection:
        rows = connection.execute('''SELECT reserved_tokens,input_tokens,chat_output_tokens
            FROM agent_model_token_reservation JOIN agent_model_usage USING (tenant_code,run_id,step_no)
            WHERE tenant_code='TENANT_A' ORDER BY input_tokens''').fetchall()
    assert [tuple(row.values()) for row in rows] == [(12, 3, 1), (12, 9, 3)]


@pytest.mark.parametrize('outcome', ['missing', 'timeout', 'cancel'])
def test_unknown_usage_keeps_reservation_and_stops_new_calls(store, outcome):
    configure('TENANT_A', 20, 20)
    original = enqueue(store, 'unknown')
    calls = []

    def provider(req):
        calls.append(outcome)
        if outcome == 'timeout':
            raise httpx.ReadTimeout('synthetic timeout', request=req)
        if outcome == 'cancel':
            assert store.cancel('TENANT_A', original)['state'] == 'CANCELLED'
            return usage(1, 1)
        output = answer()
        del output['usage']
        return response(output)

    runtime = worker(store, provider)
    first = runtime.run_once('TENANT_A')
    assert first['state'] == {'missing': 'SUCCEEDED', 'timeout': 'RETRY_WAIT', 'cancel': 'CANCELLED'}[outcome]
    if outcome == 'timeout':
        with admin() as connection:
            connection.execute('UPDATE agent_run SET ready_at=clock_timestamp() WHERE run_id=%s', (original,))
        denied = runtime.run_once('TENANT_A')
        assert (denied['error_code'], denied['model_calls'], denied['retry_count']) == ('MODEL_TOKEN_QUOTA_EXCEEDED', 1, 1)
    following = enqueue(store, 'following')
    blocked = runtime.run_once('TENANT_A')
    assert (blocked['run_id'], blocked['error_code'], blocked['model_calls']) == (following, 'MODEL_TOKEN_QUOTA_EXCEEDED', 0)
    assert calls == [outcome]
    with admin() as connection:
        assert connection.execute('''SELECT reserved_tokens FROM agent_model_token_reservation
            WHERE run_id=%s''', (original,)).fetchone() == {'reserved_tokens': 20}
        assert connection.execute('SELECT count(*) AS n FROM agent_model_usage').fetchone() == {'n': 0}


def test_underestimated_usage_is_charged_and_prior_utc_day_does_not_block(store):
    configure('TENANT_A', 10, 4)
    first = enqueue(store, 'overage')
    maximum = 9223372036854775807
    assert worker(store, lambda _: usage(maximum, maximum)).run_once('TENANT_A')['state'] == 'SUCCEEDED'
    second = enqueue(store, 'denied')
    denied = worker(store, lambda _: pytest.fail('observed overage reached provider')).run_once('TENANT_A')
    assert (denied['run_id'], denied['error_code']) == (second, 'MODEL_TOKEN_QUOTA_EXCEEDED')
    with admin() as connection:
        connection.execute("UPDATE agent_model_token_reservation SET budget_day=(clock_timestamp() AT TIME ZONE 'UTC')::date-1 WHERE run_id=%s", (first,))
    third = enqueue(store, 'new-day')
    accepted = worker(store, lambda _: usage(1, 2)).run_once('TENANT_A')
    assert (accepted['run_id'], accepted['state']) == (third, 'SUCCEEDED')
    with admin() as connection:
        assert connection.execute('''SELECT budget_day=(clock_timestamp() AT TIME ZONE 'UTC')::date AS today
            FROM agent_model_token_reservation WHERE run_id=%s''', (third,)).fetchone() == {'today': True}


def test_budget_rows_are_tenant_scoped_append_only_and_readiness_rejects_column_writes(store, monkeypatch):
    configure('TENANT_A', 12, 12)
    original = enqueue(store, 'owned')
    assert worker(store, lambda _: usage(3, 1)).run_once('TENANT_A')['state'] == 'SUCCEEDED'
    with store._scope('TENANT_B') as connection:
        assert connection.execute('SELECT * FROM agent_model_token_reservation').fetchall() == []
        with pytest.raises(psycopg.errors.InsufficientPrivilege), connection.transaction():
            connection.execute('''INSERT INTO agent_model_token_reservation
                VALUES('TENANT_A',%s,1,CURRENT_DATE,12)''', (original,))
    with store._scope('TENANT_A') as connection:
        assert connection.execute('SELECT reserved_tokens FROM agent_model_token_reservation').fetchone() == {'reserved_tokens': 12}
        for statement in ('UPDATE agent_model_token_reservation SET reserved_tokens=1',
                          'DELETE FROM agent_model_token_reservation', 'TRUNCATE agent_model_token_reservation',
                          'UPDATE agent_model_quota SET daily_token_limit=1000'):
            with pytest.raises(psycopg.errors.InsufficientPrivilege), connection.transaction():
                connection.execute(statement)
    monkeypatch.setattr(schema_guard, 'connect', lambda **kwargs: psycopg.connect(
        os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN'], row_factory=dict_row, **kwargs))
    assert schema_guard.runtime_missing() == {}
    with admin() as connection:
        connection.execute('GRANT UPDATE(reserved_tokens) ON agent_model_token_reservation TO supportflow_ai')
    try:
        assert schema_guard.runtime_missing()['ai_db.agent_model_token_reservation.runtime_permissions'] == schema_guard.MODEL_TOKEN_BUDGET_SOURCE
    finally:
        with admin() as connection:
            connection.execute('REVOKE UPDATE(reserved_tokens) ON agent_model_token_reservation FROM supportflow_ai')
