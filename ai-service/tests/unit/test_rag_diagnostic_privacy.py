"""Synthetic question/filename/credential markers must not enter app diagnostics."""
import asyncio
import logging
from types import SimpleNamespace, ModuleType
import sys

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest

from ai.api import kb, rag as rag_api
from ai.rag import embedding, graph, llama_engine, pipeline, retriever, store, parser
from ai.services import rag

PRIVATE = 'private-query-725013'
RAW = 'synthetic-upstream-credential-914629'
FILE = 'synthetic-private-customer-document-672051.txt'


def fail(*args, **kwargs): raise RuntimeError(RAW)


def safe_logs(caplog):
    # HTTP access/proxy/client transport logs are a separate deployment boundary.
    output = '\n'.join(logging.Formatter().format(record)
                       for record in caplog.records if record.name.startswith('ai.'))
    assert all(marker not in output for marker in (PRIVATE, RAW, FILE))


@pytest.fixture(autouse=True)
def app_logs(caplog): caplog.set_level(logging.INFO)


@pytest.mark.parametrize('kind', ['ask', 'search'])
def test_upstream_failure_has_fixed_http_error_and_no_raw_question_or_exception(monkeypatch, caplog, kind):
    async def ask(**kwargs): fail()
    monkeypatch.setattr(rag_api.graph, 'ask', ask)
    monkeypatch.setattr(kb.pipeline, 'search', fail)
    app = FastAPI()
    app.include_router(rag_api.router, prefix='/api')
    app.include_router(kb.router, prefix='/api')
    path = '/api/ai/v1/rag/ask' if kind == 'ask' else '/api/ai/v1/kb/search'
    body = dict(tenantCode='TENANT_A', **({'question': PRIVATE} if kind == 'ask' else {'query': PRIVATE}))
    with TestClient(app) as client:
        result = client.post(path, json=body, headers={'X-Request-Id': 'privacy-test'})
    assert result.status_code == 500 and RAW not in result.text and PRIVATE not in result.text
    safe_logs(caplog)


def test_parse_error_keeps_bad_request_semantics_without_exposing_raw_detail(caplog):
    result = kb._fail(parser.ParseError(RAW))
    assert result.status_code == 400 and RAW not in str(result.detail)
    safe_logs(caplog)


def test_successful_question_log_contains_only_metadata(monkeypatch, caplog):
    async def ask(**kwargs): return dict(answer='synthetic answer', citations=[], steps=[])
    monkeypatch.setattr(rag_api.graph, 'ask', ask)
    app = FastAPI()
    app.include_router(rag_api.router, prefix='/api')
    with TestClient(app) as client:
        result = client.post('/api/ai/v1/rag/ask', json=dict(tenantCode='TENANT_A',
                             question=PRIVATE), headers={'X-Request-Id': 'privacy-test'})
    assert result.status_code == 200
    safe_logs(caplog)


def test_retrieval_failure_returns_a_stable_code_and_no_exception_body(monkeypatch, caplog):
    monkeypatch.setattr(retriever.pipeline, 'search', fail)
    result = retriever.retrieve_with_meta(PRIVATE, 'TENANT_A')
    assert result['mode'] == 'error' and result['error'] == 'RAG_RETRIEVAL_FAILED'
    safe_logs(caplog)


@pytest.mark.parametrize('engine', [pipeline._builtin_search, llama_engine.search])
def test_keyword_fallback_diagnostics_do_not_contain_the_original_query(monkeypatch, caplog, engine):
    monkeypatch.setattr(pipeline.embedding_module, 'embed_texts', lambda *a, **k: ([[1]], 'local-hash'))
    monkeypatch.setattr(llama_engine, '_embed_texts', lambda *a, **k: ([[1]], 'local-hash'))
    monkeypatch.setattr(store, 'keyword_search', lambda *a, **k: [])
    assert engine('TENANT_A', PRIVATE)['results'] == []
    safe_logs(caplog)


def test_grade_diagnostics_do_not_log_query_text(monkeypatch, caplog):
    monkeypatch.setattr(graph, 'get_settings', lambda: SimpleNamespace(
        rag_vector_min_score=.35, rag_keyword_min_score=.12))
    assert graph.node_grade(dict(question=PRIVATE, query=PRIVATE, hits=[], steps=[]))['enough'] is False
    safe_logs(caplog)


def test_remote_embedding_failure_keeps_local_fallback_without_raw_provider_detail(monkeypatch, caplog):
    monkeypatch.setattr(embedding, 'get_settings', lambda: SimpleNamespace(
        embedding_key=lambda: 'synthetic-test-key', embedding_provider='fixture'))
    monkeypatch.setattr(embedding, '_embed_remote', fail)
    vectors, source = embedding.embed_texts([PRIVATE], tenant_code='TENANT_A', trace_id='privacy-test')
    assert source == 'local-hash' and len(vectors[0]) == 1024
    safe_logs(caplog)


def test_llama_embedding_uses_the_same_safe_fallback(monkeypatch, caplog):
    monkeypatch.setattr(embedding, 'get_settings', lambda: SimpleNamespace(
        embedding_key=lambda: 'synthetic-test-key', embedding_provider='fixture'))
    monkeypatch.setattr(embedding, '_embed_remote', fail)
    vectors, source = llama_engine._embed_texts([PRIVATE], 'TENANT_A')
    assert source == 'local-hash' and len(vectors[0]) == 1024
    safe_logs(caplog)


def test_no_model_fallback_preserves_owner_answer_without_logging_question(monkeypatch, caplog):
    monkeypatch.setattr(rag, 'resolve_provider', lambda *_: (None, 'fixture'))
    answer, citations = asyncio.run(rag.compose_answer(
        PRIVATE, [dict(doc_id=1, content='safe policy', score=1)], tenant_code='TENANT_A'))
    assert 'safe policy' in answer and citations
    safe_logs(caplog)


def test_ping_failure_does_not_log_raw_database_exception(monkeypatch, caplog):
    monkeypatch.setattr(store, 'connect', fail)
    assert store.ping() is False
    safe_logs(caplog)


@pytest.mark.parametrize('stage', ['models', 'repair-docs'])
def test_vector_diagnostics_fallback_does_not_log_traceback_or_raw_exception(monkeypatch, caplog, stage):
    calls = []

    class Connection:
        def __enter__(self):
            calls.append(1)
            if stage == 'models' or len(calls) > 1:
                fail()
            return self

        def __exit__(self, *args): pass
        def execute(self, *args): return self
        def fetchall(self): return [dict(model='local-hash', chunks=1)]
    monkeypatch.setattr(store, 'tenant_connection', lambda *_: Connection())
    assert RAW not in str(kb._vector_quality('TENANT_A', include_document_ids=True))
    safe_logs(caplog)


def test_index_api_does_not_log_private_filename(monkeypatch, caplog):
    monkeypatch.setattr(kb.pipeline, 'index_document', lambda **k: SimpleNamespace(chunk_count=1))
    monkeypatch.setattr(kb, '_result_payload', lambda _: dict(status='ok'))
    app = FastAPI()
    app.include_router(kb.router, prefix='/api')
    with TestClient(app) as client:
        result = client.post('/api/ai/v1/kb/index', params=dict(tenant_code='TENANT_A', doc_id=1,
                             file_name=FILE), content=b'fixture', headers={'X-Request-Id': 'privacy-test'})
    assert result.status_code == 200
    safe_logs(caplog)


@pytest.mark.parametrize('engine', ['builtin', 'llama'])
def test_index_success_diagnostics_hide_filename_but_keep_result_metadata(monkeypatch, caplog, engine):
    chunk = SimpleNamespace(index=0, content='safe policy', char_count=11, token_count=2)
    monkeypatch.setattr(store, 'replace_chunks', lambda *a, **kw: 1)
    monkeypatch.setattr(embedding, 'embed_texts', lambda *a, **k: ([[1]], 'local-hash'))
    if engine == 'builtin':
        monkeypatch.setattr(pipeline, 'get_settings', lambda: SimpleNamespace(
            kb_max_file_mb=1, kb_chunk_size=100, kb_chunk_overlap=0))
        monkeypatch.setattr(pipeline.parser_module, 'parse', lambda *a: 'safe policy')
        monkeypatch.setattr(pipeline.splitter_module, 'split_text', lambda *a, **k: [chunk])
        result = pipeline._builtin_index_document('TENANT_A', 1, FILE, b'fixture')
    else:
        monkeypatch.setattr(llama_engine, '_read_file', lambda *a: [])
        monkeypatch.setattr(llama_engine, '_splitter', lambda: SimpleNamespace(
            get_nodes_from_documents=lambda *_: []))
        monkeypatch.setattr(llama_engine, '_nodes_to_chunks', lambda *_: [chunk])
        result = llama_engine.index_document('TENANT_A', 1, FILE, b'fixture')
    assert result.file_name == FILE and result.chunk_count == 1
    safe_logs(caplog)


def test_optional_reader_fallback_does_not_log_filename_or_exception(monkeypatch, caplog):
    module = ModuleType('llama_index.core')
    module.Document = lambda **kwargs: SimpleNamespace(**kwargs)
    module.SimpleDirectoryReader = lambda **kwargs: SimpleNamespace(load_data=fail)
    monkeypatch.setitem(sys.modules, 'llama_index.core', module)
    monkeypatch.setattr(parser, 'parse', lambda *a: 'safe policy')
    assert llama_engine._read_file(FILE, b'fixture')[0].text == 'safe policy'
    safe_logs(caplog)
