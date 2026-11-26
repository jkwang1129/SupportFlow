"""Real pgvector/keyword SQL under a non-owner KB role; embeddings are synthetic."""
from itertools import count
import os

import psycopg
from psycopg.rows import dict_row
import pytest

from ai.rag import store, pipeline, llama_engine

pytestmark = pytest.mark.skipif(not os.getenv('SUPPORTFLOW_KB_ADMIN_TEST_DSN'),
                                reason='run scripts/test-customer-rls.py --with-ai')
IDS = count(18_000_001, 10)
VECTOR = [1.0] + [0.0] * 1023


@pytest.fixture
def corpus(monkeypatch):
    base = next(IDS)
    allowed, excluded, deleted, foreign = range(base, base + 4)
    with psycopg.connect(os.environ['SUPPORTFLOW_KB_ADMIN_TEST_DSN']) as db:
        for doc, tenant, removed in ((allowed, 'SCOPE_A', False), (excluded, 'SCOPE_A', False), (deleted, 'SCOPE_A', True), (foreign, 'SCOPE_B', False)):
            db.execute("INSERT INTO kb_document(id,tenant_code,doc_no,title,status,is_deleted) VALUES(%s,%s,%s,'synthetic scope policy',3,%s)",
                       (doc, tenant, 'SCOPE_' + str(doc), removed))
            db.execute("""INSERT INTO kb_chunk(id,tenant_code,doc_id,chunk_no,content,embedding,embedding_model)
                VALUES(%s,%s,%s,0,'退款到账 synthetic policy',%s::vector,'scope-fixture')""",
                       (doc, tenant, doc, store.to_pgvector(VECTOR)))
    monkeypatch.setattr(store, 'connect', lambda: psycopg.connect(
        os.environ['SUPPORTFLOW_KB_TEST_DSN'], row_factory=dict_row))
    return allowed, excluded, deleted, foreign


def test_both_real_sql_paths_enforce_scope_tenant_deletion_and_empty_selection(corpus):
    allowed, excluded, deleted, foreign = corpus
    for query in (lambda scope: store.search('SCOPE_A', VECTOR, doc_ids=scope),
                  lambda scope: store.keyword_search('SCOPE_A', '退款到账', doc_ids=scope)):
        assert {row['doc_id'] for row in query([allowed, foreign])} == {allowed}
        assert query([foreign, deleted]) == []
        assert query([]) == []
        assert {row['doc_id'] for row in query(None)}.issuperset({allowed, excluded})
        assert all(row['doc_id'] not in (deleted, foreign) for row in query(None))
    with store.connect() as db:
        identity = db.execute('SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user').fetchone()
        assert identity == dict(rolsuper=False, rolbypassrls=False)
        assert db.execute('SELECT * FROM kb_chunk').fetchall() == []


@pytest.mark.parametrize('engine', ['builtin', 'llama'])
@pytest.mark.parametrize('mode', ['local', 'zero', 'miss'])
def test_real_keyword_fallback_cannot_expand_requested_document_selection(corpus, monkeypatch, engine, mode):
    allowed, excluded, deleted, foreign = corpus
    vectors, source = ([VECTOR], 'local-hash') if mode == 'local' else ([[0.0] * 1024],
                                                                        'fixture-remote') if mode == 'zero' else ([VECTOR], 'fixture-remote')
    monkeypatch.setattr(pipeline.llama_engine, 'available', lambda: engine == 'llama')
    monkeypatch.setattr(pipeline.embedding_module, 'embed_texts', lambda *a, **k: (vectors, source))
    monkeypatch.setattr(llama_engine, '_embed_texts', lambda *a, **k: (vectors, source))
    scope = [deleted, foreign] if mode == 'miss' else [allowed]
    result = pipeline.search('SCOPE_A', '退款到账', doc_ids=scope)
    assert 'keyword' in result['mode']
    assert {row['doc_id'] for row in result['results']} == (set() if mode == 'miss' else {allowed})
