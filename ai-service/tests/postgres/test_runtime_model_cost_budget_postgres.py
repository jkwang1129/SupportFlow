"""Model cost admission and settlement against a disposable PostgreSQL ledger."""
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from hashlib import sha256
import json
import os
from threading import Barrier, Event

import httpx
import psycopg
import pytest

from ai.runtime.usage_pricing import parse_price_book
from test_runtime_embeddings import profile as embedding_profile
from test_runtime_model_token_budget_postgres import enqueue, usage, worker
from test_runtime_models import profile
from test_runtime_store_postgres import acquire, admin, store


pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),
                                reason='requires disposable runtime PostgreSQL')


def price_book(tenant='TENANT_A', *, version='v1', currency='USD', rates=None):
    if rates is None:
        rates = {profile(tenant=tenant).definition_hash(): {
            'input_micros_per_million': 2_000_000, 'output_micros_per_million': 7_000_000}}
    return json.dumps({'schema_version': 'runtime-price-book-v1', 'tenant': tenant,
                       'version': version, 'currency': currency, 'rates': rates})


def configure_cost(tenant, book, *, daily=None, monthly=None, allowance=12):
    with admin() as db:
        db.execute('''INSERT INTO agent_model_quota(tenant_code,max_active_steps,tokens_per_attempt,
                daily_cost_limit_picos,monthly_cost_limit_picos,price_book)
            VALUES (%s,8,%s,%s,%s,%s) ON CONFLICT(tenant_code) DO UPDATE SET
                tokens_per_attempt=EXCLUDED.tokens_per_attempt,daily_cost_limit_picos=EXCLUDED.daily_cost_limit_picos,
                monthly_cost_limit_picos=EXCLUDED.monthly_cost_limit_picos,price_book=EXCLUDED.price_book''',
                   (tenant, allowance, daily, monthly, book))


def cost_days(db):
    return db.execute('''SELECT tenant_code,budget_day,currency,charged_picos
        FROM agent_model_cost_day ORDER BY tenant_code,budget_day,currency''').fetchall()


def assert_cost_days_match_sources(db):
    raw = db.execute('''SELECT r.tenant_code,r.budget_day,r.currency,
        sum(coalesce(u.input_tokens::numeric*r.input_rate + coalesce(u.chat_output_tokens,0)::numeric*coalesce(r.output_rate,0),
                     r.reserved_cost_picos)) AS charged_picos
        FROM agent_model_token_reservation r LEFT JOIN agent_model_usage u USING(tenant_code,run_id,step_no)
        WHERE r.currency IS NOT NULL GROUP BY r.tenant_code,r.budget_day,r.currency
        ORDER BY r.tenant_code,r.budget_day,r.currency''').fetchall()
    assert cost_days(db) == raw


def assert_rejected(result, expected):
    assert (result['state'], result['error_code']) == ('FAILED', expected)
    assert (result['model_calls'], result['retry_count'], result['step_count']) == (0, 0, 0)


def test_concurrent_cost_reservations_and_inflight_price_rotation(store):
    old = price_book()
    configure_cost('TENANT_A', old, monthly=109_000_000)
    configure_cost('TENANT_B', price_book('TENANT_B'), daily=84_000_000)
    enqueue(store, 'parallel-first')
    enqueue(store, 'parallel-second')
    enqueue(store, 'independent', 'TENANT_B')
    barrier, entered, release = Barrier(2), Event(), Event()

    def waiting_provider(_):
        entered.set()
        assert release.wait(15)
        return usage(3, 1)

    new = price_book(version='v2', rates={profile().definition_hash(): {
        'input_micros_per_million': 3_000_000, 'output_micros_per_million': 8_000_000}})
    with ThreadPoolExecutor(2) as pool:
        futures = [pool.submit(worker(store, waiting_provider, owner, barrier=barrier).run_once, 'TENANT_A')
                   for owner in ('cost-a1', 'cost-a2')]
        try:
            assert entered.wait(8)
            done, pending = wait(futures, timeout=8, return_when=FIRST_COMPLETED)
            assert len(done) == len(pending) == 1
            assert_rejected(done.pop().result(), 'MODEL_COST_QUOTA_EXCEEDED')
            with admin() as db:
                assert [row['charged_picos'] for row in cost_days(db)] == [84_000_000]
                assert_cost_days_match_sources(db)
            assert worker(store, lambda _: usage(2, 1), tenant='TENANT_B').run_once(
                'TENANT_B')['state'] == 'SUCCEEDED'
            configure_cost('TENANT_A', new, monthly=109_000_000)
        finally:
            release.set()
        assert pending.pop().result(timeout=10)['state'] == 'SUCCEEDED'
    with admin() as db:
        assert [row['charged_picos'] for row in cost_days(db)] == [13_000_000, 11_000_000]
    enqueue(store, 'exact-limit')
    assert worker(store, lambda _: usage(32, 0)).run_once('TENANT_A')['state'] == 'SUCCEEDED'
    enqueue(store, 'exhausted')
    assert_rejected(worker(store, lambda _: pytest.fail('cost gate reached provider')).run_once('TENANT_A'),
                    'MODEL_COST_QUOTA_EXCEEDED')
    with admin() as db:
        rows = db.execute('''SELECT price_book_version,price_book_sha256,input_rate,output_rate,reserved_cost_picos
            FROM agent_model_token_reservation WHERE tenant_code='TENANT_A' ORDER BY price_book_version''').fetchall()
        assert [tuple(row.values()) for row in rows] == [
            ('v1', sha256(old.encode()).hexdigest(), 2_000_000, 7_000_000, 84_000_000),
            ('v2', sha256(new.encode()).hexdigest(), 3_000_000, 8_000_000, 96_000_000)]
        assert [row['charged_picos'] for row in cost_days(db)] == [109_000_000, 11_000_000]
        assert_cost_days_match_sources(db)


@pytest.mark.parametrize('outcome', ['timeout', 'cancel'])
def test_unknown_cost_keeps_original_hold(store, outcome):
    configure_cost('TENANT_A', price_book(), daily=84_000_000)
    run_id = enqueue(store, 'unknown')

    def provider(req):
        if outcome == 'timeout':
            raise httpx.ReadTimeout('synthetic timeout', request=req)
        assert store.cancel('TENANT_A', run_id)['state'] == 'CANCELLED'
        return usage(1, 1)

    current = worker(store, provider).run_once('TENANT_A')
    assert current['state'] == ('RETRY_WAIT' if outcome == 'timeout' else 'CANCELLED')
    enqueue(store, 'later')
    assert_rejected(worker(store, lambda _: pytest.fail('unknown call was refunded')).run_once('TENANT_A'),
                    'MODEL_COST_QUOTA_EXCEEDED')
    with admin() as db:
        assert [row['charged_picos'] for row in cost_days(db)] == [84_000_000]
        assert db.execute('SELECT 1 FROM agent_model_usage').fetchone() is None
        assert_cost_days_match_sources(db)


@pytest.mark.parametrize(('fault', 'code'), [('invalid', 'MODEL_COST_POLICY_INVALID'), ('tenant', 'MODEL_COST_POLICY_INVALID'),
                                             ('profile', 'MODEL_PRICE_UNAVAILABLE'), ('chat_output', 'MODEL_PRICE_UNAVAILABLE')])
def test_invalid_price_book_fails_closed_before_provider(store, fault, code):
    books = {'invalid': '{', 'tenant': price_book('TENANT_B'),
             'profile': price_book(rates={'a' * 64: {'input_micros_per_million': 2}}),
             'chat_output': price_book(rates={profile().definition_hash(): {'input_micros_per_million': 2}})}
    configure_cost('TENANT_A', books[fault], monthly=100_000_000)
    enqueue(store, 'invalid-price')
    assert_rejected(worker(store, lambda _: pytest.fail(
        'invalid pricing reached provider')).run_once('TENANT_A'), code)
    with admin() as db:
        assert cost_days(db) == []


def test_cost_is_scoped_to_original_month_and_currency(store):
    history = enqueue(store, 'last-month')
    assert worker(store, lambda _: usage(9, 3)).run_once('TENANT_A')['state'] == 'SUCCEEDED'
    old = parse_price_book(price_book().encode())
    quote = old.reservation_price(profile().definition_hash(), chat=True)
    with admin() as db:
        db.execute('''INSERT INTO agent_model_token_reservation(tenant_code,run_id,step_no,budget_day,reserved_tokens,
            price_book_version,price_book_sha256,currency,input_rate,output_rate,reserved_cost_picos)
            VALUES ('TENANT_A',%s,1,date_trunc('month',clock_timestamp() AT TIME ZONE 'UTC')::date-1,12,%s,%s,%s,%s,%s,%s)''',
                   (history, *quote, quote.reserve_picos(12)))
    configure_cost('TENANT_A', price_book(currency='EUR'), monthly=84_000_000)
    enqueue(store, 'new-currency')
    assert worker(store, lambda _: usage(3, 1)).run_once('TENANT_A')['state'] == 'SUCCEEDED'
    configure_cost('TENANT_A', price_book(currency='USD'), monthly=84_000_000)
    enqueue(store, 'blocked-currency')
    assert_rejected(worker(store, lambda _: pytest.fail('mixed currency reached provider')).run_once('TENANT_A'),
                    'MODEL_COST_CURRENCY_CONFLICT')
    with store._scope('TENANT_B') as db:
        assert cost_days(db) == []
        assert db.execute('SELECT price_book FROM agent_model_quota').fetchone() is None
    with store._scope('TENANT_A') as db:
        for statement in ('UPDATE agent_model_cost_day SET charged_picos=0', 'DELETE FROM agent_model_cost_day',
                          'UPDATE agent_model_token_reservation SET input_rate=0', 'UPDATE agent_model_quota SET price_book=NULL'):
            with pytest.raises(psycopg.errors.InsufficientPrivilege), db.transaction():
                db.execute(statement)
        assert [(row['currency'], row['charged_picos'])
                for row in cost_days(db)] == [('USD', 39_000_000), ('EUR', 13_000_000)]
        assert_cost_days_match_sources(db)


def test_nested_embedding_charges_input_only_and_preserves_cost_rejection(store):
    from test_knowledge_tools_postgres import counts, runner

    book = price_book(rates={embedding_profile().definition_hash(): {
        'input_micros_per_million': 2_000_000, 'output_micros_per_million': 10_000_000}})
    configure_cost('TENANT_A', book, daily=24_000_000)
    enqueue(store, 'priced-embedding')
    counters = counts()
    assert runner(store, {}, counters).run_once('TENANT_A')['state'] == 'SUCCEEDED'
    enqueue(store, 'cost-exhausted')
    blocked = runner(store, {}, counters).run_once('TENANT_A')
    assert (blocked['state'], blocked['error_code'], blocked['model_calls'], blocked['retry_count']) == (
        'FAILED', 'MODEL_COST_QUOTA_EXCEEDED', 0, 0)
    assert counters == dict(source=2, embedding=1, knowledge=1)
    with admin() as db:
        row = db.execute(
            'SELECT reserved_cost_picos,input_rate,output_rate FROM agent_model_token_reservation').fetchone()
        assert row == {'reserved_cost_picos': 24_000_000, 'input_rate': 2_000_000, 'output_rate': None}
        assert [row['charged_picos'] for row in cost_days(db)] == [16_000_000]
        assert_cost_days_match_sources(db)


def test_provider_overage_uses_exact_picos_and_blocks_future_calls(store):
    high_rate = 10**12
    maximum = 2**63 - 1
    book = price_book(rates={profile().definition_hash(): {
        'input_micros_per_million': high_rate, 'output_micros_per_million': high_rate}})
    configure_cost('TENANT_A', book, monthly=24 * high_rate)
    enqueue(store, 'overage')
    assert worker(store, lambda _: usage(maximum, maximum)).run_once('TENANT_A')['state'] == 'SUCCEEDED'
    enqueue(store, 'blocked')
    assert_rejected(worker(store, lambda _: pytest.fail('overage reached provider')).run_once('TENANT_A'),
                    'MODEL_COST_QUOTA_EXCEEDED')
    with admin() as db:
        assert [row['charged_picos'] for row in cost_days(db)] == [2 * maximum * high_rate]
        assert_cost_days_match_sources(db)


def test_explicit_free_price_at_zero_cost_limit_still_respects_token_limit(store):
    book = price_book(rates={profile().definition_hash(): {
        'input_micros_per_million': 0, 'output_micros_per_million': 0}})
    configure_cost('TENANT_A', book, daily=0, monthly=0)
    enqueue(store, 'explicit-free-model')
    assert worker(store, lambda _: usage(3, 1)).run_once('TENANT_A')['state'] == 'SUCCEEDED'
    with admin() as db:
        assert cost_days(db)[0]['charged_picos'] == 0
        db.execute("UPDATE agent_model_quota SET daily_token_limit=15 WHERE tenant_code='TENANT_A'")
    enqueue(store, 'token-gate')
    assert_rejected(worker(store, lambda _: pytest.fail('free model bypassed token gate')).run_once('TENANT_A'),
                    'MODEL_TOKEN_QUOTA_EXCEEDED')


def test_cost_settlement_is_atomic_and_duplicate_receipts_are_not_charged_twice(store):
    configure_cost('TENANT_A', price_book(), monthly=84_000_000)
    run_id = enqueue(store, 'rolled-back-receipt')
    lease = acquire(store)
    step, _ = store.reserve_step(lease, 'model', 'MODEL', 'NONE', {
        'operation': 'model.chat', 'input_ref': profile().definition_hash()})
    sql = '''INSERT INTO agent_model_usage(tenant_code,run_id,step_no,fence,intent_hash,payload_id,payload_hash,input_tokens,chat_output_tokens)
        VALUES ('TENANT_A',%s,1,%s,%s,gen_random_uuid(),repeat('a',64),3,1)'''
    params = (run_id, step['fence'], step['intent_hash'])
    with pytest.raises(ValueError, match='rollback'), store._scope('TENANT_A') as db:
        db.execute(sql, params)
        assert cost_days(db)[0]['charged_picos'] == 13_000_000
        raise ValueError('rollback')
    with store._scope('TENANT_A') as db:
        assert db.execute('SELECT 1 FROM agent_model_usage').fetchone() is None
        assert cost_days(db)[0]['charged_picos'] == 84_000_000
        db.execute(sql, params)
    with pytest.raises(psycopg.errors.UniqueViolation), store._scope('TENANT_A') as db:
        db.execute(sql, params)
    with store._scope('TENANT_A') as db:
        assert cost_days(db)[0]['charged_picos'] == 13_000_000
        assert_cost_days_match_sources(db)
