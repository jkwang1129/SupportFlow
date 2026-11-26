"""Scope propagation, empty-scope denial and DTO compatibility; no real models."""
import asyncio
from types import SimpleNamespace

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest

from ai.api import rag as api
from ai.api import kb as kb_api
from ai.rag import pipeline, llama_engine, store, graph
from ai.services import rag


def unexpected(*args, **kwargs): raise AssertionError('Empty/invalid scope must not access embedding/store/generation')


@pytest.mark.parametrize('engine', [pipeline._builtin_search, llama_engine.search])
@pytest.mark.parametrize('source,vectors', [('local-hash', [[1.0]]), ('fixture-remote', []), ('fixture-remote', [[0.0]])])
def test_all_keyword_fallbacks_preserve_the_requested_document_scope(monkeypatch, engine, source, vectors):
    monkeypatch.setattr(pipeline.embedding_module, 'embed_texts', lambda *a, **k: (vectors, source))
    monkeypatch.setattr(llama_engine, '_embed_texts', lambda *a, **k: (vectors, source))
    calls = []
    def keyword(*args, **kwargs): calls.append(kwargs); return []
    monkeypatch.setattr(store, 'keyword_search', keyword)
    monkeypatch.setattr(store, 'search', lambda *a, **k: [])
    engine('TENANT_A', 'synthetic refund', doc_ids=[101])
    assert calls and all(call.get('doc_ids') == [101] for call in calls)


@pytest.mark.parametrize('engine', [pipeline.search, pipeline._builtin_search, llama_engine.search])
def test_empty_scope_does_not_embed_or_fall_back_to_all_documents(monkeypatch, engine):
    monkeypatch.setattr(pipeline.embedding_module, 'embed_texts', unexpected)
    monkeypatch.setattr(llama_engine, '_embed_texts', unexpected)
    monkeypatch.setattr(store, 'keyword_search', unexpected)
    assert engine('TENANT_A', 'synthetic refund', doc_ids=[])['results'] == []


def test_empty_store_scope_never_opens_a_connection(monkeypatch):
    monkeypatch.setattr(store, 'connect', unexpected)
    assert store.search('TENANT_A', [1.0], doc_ids=[]) == []
    assert store.keyword_search('TENANT_A', 'synthetic refund', doc_ids=[]) == []


@pytest.mark.parametrize('scope', [[True], ['101'], [0], [-1], [9223372036854775808], list(range(1, 202)), '101'])
@pytest.mark.parametrize('engine', [pipeline.search, llama_engine.search])
def test_invalid_scope_is_rejected_before_embedding_or_database_access(monkeypatch, engine, scope):
    monkeypatch.setattr(pipeline.embedding_module, 'embed_texts', unexpected)
    monkeypatch.setattr(llama_engine, '_embed_texts', unexpected)
    with pytest.raises(ValueError):
        engine('TENANT_A', 'synthetic refund', doc_ids=scope)


@pytest.mark.parametrize('engine', ['linear', 'langgraph'])
@pytest.mark.parametrize('scope', [None, [], [101]])
def test_graph_preserves_scope_and_empty_scope_abstains_without_search_or_generation(monkeypatch, engine, scope):
    installed = graph.langgraph_available()
    if engine == 'langgraph' and not installed:
        pytest.skip('requires test LangGraph dependency')
    monkeypatch.setattr(graph, 'langgraph_available', lambda: engine == 'langgraph')
    monkeypatch.setattr(graph, '_graph', None)
    monkeypatch.setattr(graph, 'get_settings', lambda: SimpleNamespace(
        rag_vector_min_score=.35, rag_keyword_min_score=.12))
    seen = []

    def retrieve(**kwargs):
        seen.append(kwargs)
        assert kwargs['doc_ids'] == scope
        return dict(mode='vector', results=[dict(doc_id=101, content='synthetic policy', score=1)])

    async def generate(**kwargs):
        if scope == []:
            unexpected()
        return rag.ComposedAnswer('synthetic answer', [], True)
    monkeypatch.setattr(graph.retriever, 'retrieve_with_meta', retrieve)
    monkeypatch.setattr(rag, 'compose_answer_details', generate)
    result = asyncio.run(graph.ask('synthetic question', 'TENANT_A', doc_ids=scope))
    assert len(seen) == (0 if scope == [] else 1)
    if scope == []:
        assert result['decision'] == 'abstain' and result['abstention_reason'] == 'empty_document_scope'
        assert not result['citations'] and all(step['node'] != 'rewrite' for step in result['steps'])


@pytest.mark.parametrize('body,expected', [({}, None), ({'docIds': None}, None), ({'docIds': []}, []), ({'docIds': [101]}, [101])])
def test_api_distinguishes_omitted_from_explicit_empty_scope(monkeypatch, body, expected):
    calls = []
    async def ask(**kwargs): calls.append(kwargs); return dict(citations=[], steps=[])
    monkeypatch.setattr(api.graph, 'ask', ask)
    app = FastAPI()
    app.include_router(api.router, prefix='/api')
    with TestClient(app) as client:
        result = client.post('/api/ai/v1/rag/ask', json=dict(tenantCode='TENANT_A',
                             question='synthetic question', **body), headers={'X-Request-Id': 'scope-test'})
    assert result.status_code == 200 and calls[0]['doc_ids'] == expected


@pytest.mark.parametrize('scope', [[True], ['101'], [0], [-1], list(range(1, 202))])
def test_api_rejects_invalid_scope_before_graph_execution(monkeypatch, scope):
    monkeypatch.setattr(api.graph, 'ask', unexpected)
    app = FastAPI()
    app.include_router(api.router, prefix='/api')
    with TestClient(app) as client:
        result = client.post('/api/ai/v1/rag/ask', json=dict(tenantCode='TENANT_A',
                             question='synthetic', docIds=scope), headers={'X-Request-Id': 'scope-test'})
    assert result.status_code == 422


@pytest.mark.parametrize('body,expected', [({}, None), ({'docIds': None}, None), ({'docIds': []}, []), ({'docIds': [101]}, [101])])
def test_kb_search_api_keeps_the_same_document_scope_contract(monkeypatch, body, expected):
    calls = []
    def search(**kwargs): calls.append(kwargs); return dict(results=[])
    monkeypatch.setattr(kb_api.pipeline, 'search', search)
    app = FastAPI()
    app.include_router(kb_api.router, prefix='/api')
    with TestClient(app) as client:
        result = client.post('/api/ai/v1/kb/search', json=dict(tenantCode='TENANT_A',
                             query='synthetic', **body), headers={'X-Request-Id': 'scope-test'})
    assert result.status_code == 200 and calls[0]['doc_ids'] == expected


@pytest.mark.parametrize('scope', [[True], ['101'], [0], [-1], list(range(1, 202))])
def test_kb_search_api_rejects_invalid_scope_before_retrieval(monkeypatch, scope):
    monkeypatch.setattr(kb_api.pipeline, 'search', unexpected)
    app = FastAPI()
    app.include_router(kb_api.router, prefix='/api')
    with TestClient(app) as client:
        result = client.post('/api/ai/v1/kb/search', json=dict(tenantCode='TENANT_A',
                             query='synthetic', docIds=scope), headers={'X-Request-Id': 'scope-test'})
    assert result.status_code == 422
