"""Visitor answer decisions and exact quotes must be verified at the graph entry point."""
import asyncio
import json
from types import SimpleNamespace

import pytest

from ai.rag import graph, grounded_reply
from ai.services import rag

FACT = '工作日16点前付款的普通快递订单当日发货。'
OTHER_FACT = '16点及之后付款的订单下一个工作日发货。'
HITS = [dict(id=21, doc_id=31, chunk_no=0, content=FACT + OTHER_FACT, score=.8),
        dict(id=22, doc_id=32, chunk_no=0, content='普通电子发票在申请通过后2个工作日内发送。', score=.6)]
ACTUAL_GRAPH = graph.langgraph_available()


def reply(*sources, text='今天发货。'):
    return json.dumps({'decision': 'answer', 'claims': [{'text': text, 'sources': list(sources)}]}, ensure_ascii=False)


@pytest.fixture(params=['linear', 'langgraph'])
def path(monkeypatch, request):
    if request.param == 'langgraph' and not ACTUAL_GRAPH:
        pytest.skip('Install AI test requirements for the actual graph')
    monkeypatch.setattr(graph, 'langgraph_available', lambda: request.param == 'langgraph')
    monkeypatch.setattr(graph, '_graph', None)
    settings = SimpleNamespace(rag_require_grounded_reply=True, rag_require_extractive_support=False, llm_timeout=5)
    monkeypatch.setattr(graph, 'get_settings', lambda: settings)
    monkeypatch.setattr(rag, 'get_settings', lambda: settings)
    monkeypatch.setattr(graph.retriever, 'retrieve_with_meta', lambda **_: {'mode': 'vector', 'results': HITS})
    monkeypatch.setattr(graph.store, 'published_evidence_is_current', lambda *_: True)
    monkeypatch.setattr(rag, 'resolve_provider', lambda _: ({'model': 'fixture'}, 'fixture'))
    return settings


def ask(monkeypatch, response, *, published=True):
    calls = []

    async def generate(_provider, payload, _timeout, **_kwargs):
        calls.append(payload)
        return {'choices': [{'message': {'content': response}}]}

    monkeypatch.setattr(rag, 'call_chat', generate)
    result = asyncio.run(graph.ask('工作日15点付款何时发货？', 'CONTRACT_A', doc_ids=[31, 32], published_only=published))
    assert len(calls) == 1
    return result, calls[0]['messages'][0]['content']


def test_valid_exact_quotes_are_used_to_render_citations(path, monkeypatch):
    result, system = ask(monkeypatch, reply({'index': 1, 'quote': FACT}))
    assert system == grounded_reply.PROMPT
    assert result['decision'] == 'answer'
    assert result['answer'] == '今天发货。[1]'
    assert [citation['doc_id'] for citation in result['citations']] == ['31']


def test_explicit_abstention_updates_the_decision_and_keeps_citations_empty(path, monkeypatch):
    result, _ = ask(monkeypatch, '{"decision":"abstain"}')
    assert result['decision'] == 'abstain'
    assert result['abstention_reason'] == 'model_insufficient_evidence'
    assert result['citations'] == [] and not result['enough']
    assert result['steps'][-1]['reason'] == 'model_insufficient_evidence'


def test_wrong_source_does_not_send_generated_text(path, monkeypatch):
    result, _ = ask(monkeypatch, reply({'index': 2, 'quote': FACT}, text='错误的发货承诺'))
    assert result['decision'] == 'abstain' and result['abstention_reason'] == 'invalid_grounded_reply'
    assert result['citations'] == [] and '错误的发货承诺' not in result['answer']


def test_staff_path_and_default_visitor_path_keep_plain_text_protocol(path, monkeypatch):
    result, system = ask(monkeypatch, '原有答复[1]', published=False)
    assert system == rag.SYSTEM_PROMPT and result['answer'] == '原有答复[1]'
    path.rag_require_grounded_reply = False
    result, system = ask(monkeypatch, '原有答复[1]', published=True)
    assert system == rag.SYSTEM_PROMPT and result['answer'] == '原有答复[1]'


def test_missing_provider_with_policy_enabled_abstains(path, monkeypatch):
    monkeypatch.setattr(rag, 'resolve_provider', lambda _: (None, ''))
    result = asyncio.run(graph.ask('何时发货？', 'CONTRACT_A', published_only=True))
    assert result['decision'] == 'abstain' and result['abstention_reason'] == 'grounded_model_unavailable'
    assert result['citations'] == [] and FACT not in result['answer']


def test_verified_quote_is_still_rejected_when_source_changes(path, monkeypatch):
    monkeypatch.setattr(graph.store, 'published_evidence_is_current', lambda *_: False)
    result, _ = ask(monkeypatch, reply({'index': 1, 'quote': FACT}))
    assert result['abstention_reason'] == 'stale_evidence'
    assert result['citations'] == result['retrieved'] == []


def test_optional_extractive_mode_still_checks_the_claim_itself(path, monkeypatch):
    path.rag_require_extractive_support = True
    result, _ = ask(monkeypatch, reply({'index': 1, 'quote': FACT}, text='当日可以发货'))
    assert result['abstention_reason'] == 'unsupported_claim'


@pytest.mark.parametrize('content', [
    '', '普通未引用文本', '{"decision":"answer","decision":"abstain"}',
    '{"decision":"abstain","claims":[]}', '{"decision":"answer","claims":[]}',
    reply(), reply({'index': True, 'quote': FACT}), reply({'index': 1.0, 'quote': FACT}),
    reply({'index': 1, 'quote': '当日发货。'}), reply({'index': 8, 'quote': FACT}),
    reply({'index': 1, 'quote': FACT}, text='预置编号[2]'),
    reply({'index': 1, 'quote': FACT}, text='第一行\n第二行'),
    reply({'index': 1, 'quote': FACT}, text='转义代理字符'+chr(0xD800)),
    reply({'index': 1, 'quote': FACT}, {'index': 1, 'quote': FACT}),
    '{"decision":"answer","claims":' + '[' * 1200 + ']' * 1200 + '}', 'x' * 6001,
])
def test_invalid_witnesses_and_invalid_schema_are_rejected(content):
    with pytest.raises(ValueError):
        grounded_reply.parse_grounded_reply(content, [{'index': 1, 'content': FACT + OTHER_FACT}])


def test_distinct_witnesses_from_same_source_render_one_reference():
    content = reply({'index': 1, 'quote': FACT}, {'index': 1, 'quote': OTHER_FACT})
    assert grounded_reply.parse_grounded_reply(content, [{'index': 1, 'content': FACT + OTHER_FACT}]) == '今天发货。[1]'


def test_quote_accepts_consecutive_complete_sentences_from_a_single_chunk():
    content = reply({'index': 1, 'quote': FACT + OTHER_FACT})
    assert grounded_reply.parse_grounded_reply(content, [{'index': 1, 'content': FACT + OTHER_FACT}]) == '今天发货。[1]'


@pytest.mark.parametrize('quote', [FACT + FACT, FACT + OTHER_FACT + FACT + '节假日另行通知。',
                                  '普通快递订单当日发货。' + OTHER_FACT])
def test_quote_cannot_splice_skip_truncate_or_exceed_the_sentence_limit(quote):
    source = FACT + OTHER_FACT + FACT + '节假日另行通知。' + FACT + OTHER_FACT + OTHER_FACT
    with pytest.raises(ValueError, match='unverified grounded reply source'):
        grounded_reply.parse_grounded_reply(reply({'index': 1, 'quote': quote}), [{'index': 1, 'content': source}])
