"""Real leases/Step budgets and recovery; customer HTTP is a deterministic protocol Stub."""
from dataclasses import replace
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
import time
from uuid import uuid4

import httpx
import psycopg
import pytest

from ai.runtime.source import SourceClient
from ai.runtime.ticket_creation import TicketCreationClient
from ai.runtime.ticket_write_tool import ticket_create_tool
from ai.runtime.tools import ToolCall, ToolExecutor, ToolRegistry
from ai.runtime.types import Limits
from ai.runtime.worker import RuntimeWorker
from test_runtime_source import KEY, response
from test_runtime_store_postgres import store, request, admin

pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'),
                                reason='requires disposable runtime PostgreSQL')


def setup(store, mode='normal', limits=None, scopes=frozenset({'ticket:create'})):
    source = request()
    row = store.enqueue(source, limits or Limits())
    snapshot = uuid4()
    state = dict(source=True, source_calls=0, posts=[], gets=0, committed=[], mode=mode, source_fail_once=False)
    payload = dict(schema_version='source-v1', trusted_source=dict(tenant_code=source.tenant, source_event_id=str(source.source_event_id), run_id=str(row['run_id']),
                                                                   session_no=source.session_no, message_id=str(source.message_id), event_seq=source.event_seq, principal_ref=source.principal_ref, customer_ref=None),
                   untrusted_input=dict(role='user', content='PRIVATE SYNTHETIC SOURCE'))
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
    now = datetime.now(timezone.utc)
    envelope = dict(code=0, message='ok', data=dict(tenant_code=source.tenant, source_event_id=str(source.source_event_id), run_id=str(row['run_id']), snapshot_id=str(snapshot),
                                                    schema_version='source-v1', payload_json=raw, payload_hash=hashlib.sha256(raw.encode()).hexdigest(), created_at=now.isoformat(), expires_at=(now + timedelta(hours=1)).isoformat()))

    def server(req):
        if req.url.path.endswith('/snapshot'):
            state['source_calls'] += 1
            assert req.method == 'POST' and req.content == b'{}'
            if state['source_fail_once'] and state['source_calls'] == 1:
                return response(b'PRIVATE UPSTREAM', status=503)
            # The existing source API uses its established HTTP 200 business envelope.
            if not state['source']:
                return response(dict(code=40402, message='PRIVATE revoked source', data=None))
            return response(envelope)
        assert req.url.path.endswith('/ticket-create')
        if req.method == 'POST':
            body = json.loads(req.content)
            state['posts'].append(body)
            # A real restricted Runtime authorization proves that the exact
            # business intent was RESERVED before the adapter made its call.
            grant = store.authorize_ticket_create(
                source.tenant, row['run_id'], source.source_event_id, body['owner'], body['fence'], body['snapshot_id'], body['arguments_sha256'])
            assert grant['operation'] == 'ticket.create' and grant['step_no'] > 0
            if state['mode'] == 'denied':
                return response(b'PRIVATE DENIAL', status=401)
            if state['mode'] != 'absent':
                state['committed'].append(1)
            if state['mode'] == 'cancel':
                store.cancel(source.tenant, row['run_id'])
            if state['mode'] in ('lost_ack', 'absent', 'lookup_down'):
                raise httpx.ReadTimeout('PRIVATE uncertain write')
        else:
            assert req.method == 'GET' and req.content == b''
            state['gets'] += 1
            if state['mode'] == 'lookup_down':
                return response(b'PRIVATE LOOKUP DOWN', status=503)
            if not state['committed']:
                return response(dict(code=40406, message='not observed', data=None), status=404)
        body = state['posts'][0]
        value = dict(tenant_code=source.tenant, source_event_id=str(source.source_event_id), run_id=str(row['run_id']), snapshot_id=body['snapshot_id'],
                     arguments_sha256=body['arguments_sha256'], schema_version='ticket-create-v1', operation='ticket.create', ticket_id='123', ticket_no='TK_123')
        if state['mode'] == 'bad_result':
            value['arguments_sha256'] = 'b' * 64
        return response(dict(code=0, message='ok', data=value))
    transport = httpx.MockTransport(server)

    def executor(key=KEY, base='http://customer.test'):
        return ToolExecutor(ToolRegistry([ticket_create_tool(SourceClient(base, key, transport=transport), TicketCreationClient(base, key, transport=transport))]), scopes)
    return source, row, state, executor


def proposal(title='PRIVATE TITLE'): return ToolCall('create_ticket',
                                                     'ticket-create-v1', dict(title=title, content='PRIVATE BODY\n原文 '))


def worker(store, handler, owner='write-tool-worker'): return RuntimeWorker(store, {'runtime-v1': handler}, owner)


def run(store, executor): return worker(store, lambda ctx: executor.execute(
    ctx, 'model-proposal', proposal())).run_once('TENANT_A')


def test_write_tool_uses_two_shared_tool_reservations_and_reference_only_steps(store):
    source, row, state, factory = setup(store)
    result = run(store, factory())
    assert result['state'] == 'SUCCEEDED' and result['result_ref']['business_ref'] == 'ticket:123'
    assert (result['step_count'], result['tool_calls'],
            result['model_calls'], result['retry_count']) == (3, 2, 0, 0)
    steps = store.steps(source.tenant, row['run_id'])
    assert [s['effect'] for s in steps] == ['NONE', 'READ', 'WRITE']
    assert all(s['state'] == 'SUCCEEDED' for s in steps) and len(
        state['posts']) == 1 and state['source_calls'] == 1 and state['gets'] == 0
    assert 'PRIVATE' not in json.dumps(steps, default=str) + json.dumps(result, default=str)
    with admin() as db:
        assert db.execute('SELECT count(*) AS n FROM agent_payload').fetchone()['n'] == 0


def test_second_model_call_key_confirms_same_ticket_without_posting_again(store):
    source, row, state, factory = setup(store)
    executor = factory()

    def handler(ctx):
        first = executor.execute(ctx, 'first', proposal())
        assert first == executor.execute(ctx, 'second', proposal())
        return first
    result = worker(store, handler).run_once(source.tenant)
    assert result['state'] == 'SUCCEEDED' and (result['step_count'], result['tool_calls']) == (4, 3)
    assert len(state['posts']) == 1 and state['source_calls'] == 1 and state['gets'] == 1


def test_lost_ack_is_confirmed_by_one_accounted_get_in_original_write(store):
    source, row, state, factory = setup(store, 'lost_ack')
    result = run(store, factory())
    assert result['state'] == 'SUCCEEDED' and (
        result['step_count'], result['tool_calls'], result['retry_count']) == (4, 3, 0)
    assert len(state['posts']) == 1 and state['gets'] == 1 and state['committed'] == [1]
    assert [s['state'] for s in store.steps(source.tenant, row['run_id'])
            if s['effect'] == 'WRITE'] == ['SUCCEEDED']


@pytest.mark.parametrize('mode', ['absent', 'lookup_down', 'denied', 'bad_result'])
def test_unconfirmed_writes_hold_unknown_and_block_next_source_without_repost(store, mode):
    source, row, state, factory = setup(store, mode)
    result = run(store, factory())
    assert result['state'] == 'FAILED' and result['error_code'] == 'SIDE_EFFECT_UNKNOWN' and result['retry_count'] == 0
    assert len(state['posts']) == 1 and state['gets'] == (1 if mode in ('absent', 'lookup_down') else 0)
    assert [s['state'] for s in store.steps(source.tenant, row['run_id']) if s['effect'] == 'WRITE'] == ['UNKNOWN']
    pending = store.enqueue(request(seq=2))
    assert store.claim_next(source.tenant, 'later-worker') is None
    assert store.get(source.tenant, pending['run_id'])['state'] == 'PENDING'


@pytest.mark.parametrize('budget', [0, 1])
def test_tool_budget_exhaustion_stops_before_source_or_post(store, budget):
    source, row, state, factory = setup(store, limits=Limits(max_tool_calls=budget))
    result = run(store, factory())
    assert result['state'] == 'FAILED' and result['error_code'] == 'BUDGET_EXHAUSTED' and result['tool_calls'] == budget
    assert state['source_calls'] == budget and state['posts'] == [] and state['gets'] == 0


def test_receipt_query_cannot_bypass_shared_budget_or_reset_unknown_write(store):
    source, row, state, factory = setup(store, 'lost_ack', limits=Limits(max_tool_calls=2))
    result = run(store, factory())
    assert result['state'] == 'FAILED' and result['error_code'] == 'SIDE_EFFECT_UNKNOWN'
    assert (result['tool_calls'], result['step_count'], result['retry_count']) == (2, 3, 0)
    assert len(state['posts']) == 1 and state['gets'] == 0 and state['committed'] == [1]


def test_source_revoked_or_missing_scope_prevents_actual_write(store):
    source, row, state, factory = setup(store)
    state['source'] = False
    result = run(store, factory())
    assert result['state'] == 'FAILED' and result['error_code'] == 'SOURCE_REVOKED' and result['tool_calls'] == 1
    assert state['posts'] == [] and not any(
        s['effect'] == 'WRITE' for s in store.steps(source.tenant, row['run_id']))


def test_missing_scope_does_not_even_reserve_a_source_authorization(store):
    source, row, state, factory = setup(store, scopes=frozenset())
    result = run(store, factory())
    assert result['state'] == 'FAILED' and result['error_code'] == 'TOOL_SCOPE_DENIED' and result['step_count'] == 0 and state['source_calls'] == 0 and state['posts'] == []


def test_cancel_during_post_cannot_persist_late_success_or_lookup_with_lost_lease(store):
    source, row, state, factory = setup(store, 'cancel')
    result = run(store, factory())
    assert result['state'] == 'FAILED' and result['error_code'] == 'SIDE_EFFECT_UNKNOWN' and result['cancel_requested']
    assert state['committed'] == [1] and len(state['posts']) == 1 and state['gets'] == 0


class LostStepAck:
    def __init__(self, actual): self.actual = actual; self.once = True
    def __getattr__(self, name): return getattr(self.actual, name)

    def complete_step(self, *args):
        result = self.actual.complete_step(*args)
        if self.once and args[2] == 'SUCCEEDED' and args[3].get('operation') == 'ticket.create':
            self.once = False
            raise psycopg.OperationalError('PRIVATE lost successful step acknowledgement')
        return result


@pytest.mark.parametrize('change', ['source_revoked', 'policy_origin', 'rotated_key', 'arguments', 'corrupt_receipt'])
def test_successful_write_restart_uses_original_binding_and_only_receipt_query(store, change):
    source, row, state, factory = setup(store)
    first = worker(LostStepAck(store), lambda ctx: factory().execute(ctx, 'first-model-call', proposal()))
    assert first.run_once(source.tenant)['state'] == 'RETRY_WAIT' and len(state['posts']) == 1
    state['source'] = False
    if change == 'corrupt_receipt':
        with admin() as db:
            db.execute(
                "UPDATE agent_step SET result_ref=jsonb_set(result_ref,'{business_ref}','\"ticket:999\"') WHERE effect='WRITE'")
    executor = factory(base='http://other.test') if change == 'policy_origin' else factory(key=KEY
                                                                                           + 'x') if change == 'rotated_key' else factory()
    result = worker(store, lambda ctx: executor.execute(ctx, 'changed-model-call-key',
                    proposal('changed' if change == 'arguments' else 'PRIVATE TITLE')), 'new-write-worker').run_once(source.tenant)
    assert len(state['posts']) == 1 and state['source_calls'] == 1 and result['retry_count'] == 1
    if change in ('source_revoked', 'rotated_key'):
        assert result['state'] == 'SUCCEEDED' and state['gets'] == 1 and (
            result['tool_calls'], result['step_count']) == (3, 4)
    else:
        assert result['state'] == 'FAILED' and state['gets'] == (1 if change == 'corrupt_receipt' else 0)
    assert [s['state'] for s in store.steps(source.tenant, row['run_id'])
            if s['effect'] == 'WRITE'] == ['SUCCEEDED']


def test_source_transient_retry_uses_original_budget_and_new_owner_fence_before_post(store):
    source, row, state, factory = setup(store)
    state['source_fail_once'] = True
    runner = worker(store, lambda ctx: factory().execute(ctx, 'k', proposal()))
    assert runner.run_once(source.tenant)['state'] == 'RETRY_WAIT' and state['posts'] == []
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        result = runner.run_once(source.tenant)
        if result is not None:
            break
        time.sleep(.05)
    else:
        raise AssertionError('Source retry did not become due')
    assert result['state'] == 'SUCCEEDED' and (
        result['tool_calls'], result['step_count'], result['retry_count']) == (3, 4, 1)
    assert state['source_calls'] == 2 and len(state['posts']) == 1 and state['posts'][0]['fence'] > 1
