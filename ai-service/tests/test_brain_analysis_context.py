"""Paired model requests and optional PostgreSQL recovery of conversation slots."""
import asyncio
import json
import os
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import httpx
import psycopg
from psycopg.rows import dict_row
import pytest

from ai.core.llm_chat import call_chat
from ai.services import brain


@pytest.fixture
def model(monkeypatch):
    captured = []
    settings = SimpleNamespace(brain_context_max_bytes=0, llm_timeout=2)
    chosen = dict(provider='fixture', model='fixture-brain', base_url='http://127.0.0.1:1/v1', api_key='synthetic-key')
    monkeypatch.setattr(brain, 'get_settings', lambda: settings)
    monkeypatch.setattr(brain, 'resolve_provider', lambda _tenant: (chosen, 'fixture'))

    def receive(request):
        raw = request.content
        captured.append((raw, json.loads(raw)))
        data = {'intent': '物流查询', 'confidence': 80, 'need_human': False}
        encoded = json.dumps({'choices': [{'message': {'content': json.dumps(data)}}]}).encode()
        return httpx.Response(200, headers={'Content-Type': 'application/json'}, stream=httpx.ByteStream(encoded))

    async def transport(*args, **kwargs):
        return await call_chat(*args, **kwargs, meter_usage=False, _transport=httpx.MockTransport(receive))

    monkeypatch.setattr(brain, 'call_chat', transport)
    return settings, captured


def analyze(question, history, known=None, *, tenant='CTXT_A'):
    return asyncio.run(brain.analyze_with_llm(question, history, [{'name': '物流查询'}], tenant, known))


def message_bytes(payload):
    return len(json.dumps(payload['messages'], ensure_ascii=False, separators=(',', ':')).encode('utf-8'))


def test_brain_analysis_context_compares_real_serialization_with_same_model_fixture(model):
    settings, captured = model
    question = '刚才那笔订单现在到哪了？📦'
    recent = {'role': 'assistant', 'content': '可以帮您查，请稍候。'}
    history = [{'role': 'user', 'content': '上一轮与问题无关。' * 450}, recent]
    memory = {'order_no': 'ORDER-123456', 'phone': '13800000000', 'other': 'SYNTHETIC_UNAPPROVED_SLOT'}
    baseline = analyze(question, history, memory)
    settings.brain_context_max_bytes = 2_048
    bounded = analyze(question, history, memory)
    assert baseline == bounded == {'intent': '物流查询', 'confidence': 80, 'need_human': False}
    (old_wire, old), (new_wire, new) = captured
    assert message_bytes(old) > 2_048 >= message_bytes(new)
    assert old['messages'][1]['content'].endswith('other=SYNTHETIC_UNAPPROVED_SLOT）')
    assert json.loads(new['messages'][1]['content']) == {
        'schema_version': 'brain-context-v1', 'question': question, 'history': [recent],
        'remembered_slots': {'order_no': 'ORDER-123456', 'phone': '13800000000'},
    }
    assert new['messages'][0]['content'].startswith(old['messages'][0]['content'])
    report = Path(__file__).resolve().parents[2]/'.local/verification/brain-analysis-context.json'
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({'mode': 'DETERMINISTIC_MODEL_FIXTURE', 'cases': 1,
        'baseline_message_bytes': message_bytes(old), 'bounded_message_bytes': message_bytes(new),
        'baseline_wire_bytes': len(old_wire), 'bounded_wire_bytes': len(new_wire), 'model_calls_each': 1,
        'observed_provider_tokens': None}, indent=2)+'\n', encoding='utf-8')


def test_brain_analysis_context_exact_budget_filters_roles_and_preserves_repeated_questions(model):
    settings, captured = model
    question = '再查一下“那一单”\n谢谢 🙂'
    history = [{'role': 'system', 'content': 'SYNTHETIC_UNTRUSTED_INSTRUCTION'}, {'role': 'user', 'content': question}]
    permissions = {'order_no': 'malformed\n value', 'phone': 'wrong', 'unknown': 'SYNTHETIC_UNAPPROVED_SLOT'}
    settings.brain_context_max_bytes = 3_000
    assert analyze(question, history, permissions)
    first = captured[-1][1]
    state = json.loads(first['messages'][1]['content'])
    assert state['history'] == [history[-1]] and state['remembered_slots'] == {}
    assert 'SYNTHETIC_UNTRUSTED_INSTRUCTION' not in json.dumps(first)
    exact = message_bytes(first)
    settings.brain_context_max_bytes = exact
    assert analyze(question, history, permissions)
    assert message_bytes(captured[-1][1]) == exact
    settings.brain_context_max_bytes = exact - 1
    assert analyze(question, history, permissions)
    smaller = json.loads(captured[-1][1]['messages'][1]['content'])
    assert smaller['question'] == question and smaller['history'] == []


@pytest.mark.parametrize('question,budget', [('客户问题' * 900, 2_048), ('客户问题', 64), ('含非法字符\ud800', 2_048)])
def test_brain_analysis_context_falls_back_without_provider_if_required_input_cannot_fit(model, question, budget):
    settings, captured = model
    settings.brain_context_max_bytes = budget
    assert analyze(question, []) is None and captured == []


@pytest.mark.skipif(not os.getenv('SUPPORTFLOW_RUNTIME_TEST_DSN'), reason='requires disposable runtime PostgreSQL')
def test_brain_analysis_context_restores_same_session_slot_and_excludes_foreign_or_deleted_rows(model, monkeypatch):
    dsn = os.environ['SUPPORTFLOW_RUNTIME_TEST_DSN']
    monkeypatch.setattr(brain, 'connect', lambda: psycopg.connect(dsn, row_factory=dict_row))
    settings, captured = model
    settings.brain_context_max_bytes = 2_048
    session = 'ctx-' + uuid4().hex
    brain.save_dialogue('CTXT_A', session, {'slots': {'order_no': 'ORDER-ALPHA1'}, 'turn_count': 1})
    brain.save_dialogue('CTXT_B', session, {'slots': {'order_no': 'ORDER-BRAVO1'}, 'turn_count': 1})
    for tenant, expected in (('CTXT_A', {'order_no': 'ORDER-ALPHA1'}), ('CTXT_B', {'order_no': 'ORDER-BRAVO1'}), ('CTXT_C', {})):
        restored = brain.load_dialogue(tenant, session)
        assert analyze('之前的订单呢？', [], restored['slots'], tenant=tenant)
        assert json.loads(captured[-1][1]['messages'][1]['content'])['remembered_slots'] == expected
    with psycopg.connect(os.environ['SUPPORTFLOW_RUNTIME_ADMIN_TEST_DSN']) as database:
        database.execute('UPDATE bot_dialogue SET is_deleted=TRUE WHERE tenant_code=%s AND session_no=%s', ('CTXT_A', session))
    restored = brain.load_dialogue('CTXT_A', session)
    assert restored['slots'] == {}
    assert analyze('之前的订单呢？', [], restored['slots'])
    assert json.loads(captured[-1][1]['messages'][1]['content'])['remembered_slots'] == {}
