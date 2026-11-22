"""Paired synthetic outputs, identical retriever/model stubs across source policies."""
import asyncio
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from ai.rag import graph
from ai.services import rag


DATASET = json.loads((Path(__file__).resolve().parents[2]/'fixtures/evaluation/rag-extractive-v1.json').read_text(encoding='utf-8'))
GRAPH_INSTALLED = graph.langgraph_available()


@pytest.mark.parametrize('engine', ['linear', 'langgraph'])
@pytest.mark.parametrize('case', DATASET['cases'], ids=lambda item: item['id'])
def test_rag_extractive_support_compares_paired_outputs(monkeypatch, engine, case):
    if engine == 'langgraph' and not GRAPH_INSTALLED:
        pytest.skip('Install requirements-test.txt to exercise the actual LangGraph path')
    monkeypatch.setattr(graph, 'langgraph_available', lambda: engine == 'langgraph')
    monkeypatch.setattr(graph, '_graph', None)
    settings = SimpleNamespace(rag_vector_min_score=.35, rag_keyword_min_score=.12, rag_require_extractive_support=False,
                               llm_timeout=1)
    monkeypatch.setattr(graph, 'get_settings', lambda: settings)
    monkeypatch.setattr(rag, 'get_settings', lambda: settings)
    monkeypatch.setattr(rag, 'resolve_provider', lambda tenant: ({'model': 'synthetic-model'}, 'synthetic-model'))
    prompts = []

    def retrieve(**kwargs):
        assert kwargs['tenant_code'] == 'RAG_FIXTURE_A' and kwargs['doc_ids'] == [101]
        return {'mode': 'vector', 'results': [dict(id=index, doc_id=101, score=.9, content=text)
                                              for index, text in enumerate(DATASET['sources'], start=1)]}

    async def provider(chosen, payload, timeout, **kwargs):
        prompts.append(payload['messages'])
        return {'choices': [{'message': {'content': case['answer']}}]}

    monkeypatch.setattr(graph.retriever, 'retrieve_with_meta', retrieve)
    monkeypatch.setattr(rag, 'call_chat', provider)
    default = asyncio.run(graph.ask(DATASET['question'], 'RAG_FIXTURE_A', doc_ids=[101]))
    settings.rag_require_extractive_support = True
    strict = asyncio.run(graph.ask(DATASET['question'], 'RAG_FIXTURE_A', doc_ids=[101]))
    assert default['decision'] == 'answer' and default['answer'] == case['answer']
    before, after = prompts
    assert before[1:] == after[1:] and before[0]['content'] + rag.EXTRACTIVE_INSTRUCTIONS == after[0]['content']
    if case['strict']:
        assert strict['decision'] == 'answer' and strict['answer'] == case['answer'] and strict['citations']
    else:
        assert strict['decision'] == 'abstain' and strict['abstention_reason'] == 'unsupported_claim'
        assert strict['citations'] == [] and strict['answer'] != case['answer']


@pytest.mark.parametrize('answer', ['', '上午开放。[1][2]', '上午开放。[1]\n' * 4])
def test_quote_contract_refuses_empty_redundant_or_unbounded_claims(answer):
    assert not rag.has_extractive_support(answer, [{'index': 1, 'content': '上午开放。'}])
