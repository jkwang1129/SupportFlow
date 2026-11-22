"""Actual publication/index predicates intersect scope, RLS and deletion."""
import asyncio
from itertools import count
import os
from types import SimpleNamespace

import psycopg
from psycopg.rows import dict_row
import pytest

from ai.rag import graph, store, pipeline, llama_engine
from ai.services import rag

pytestmark=pytest.mark.skipif(not os.getenv('SUPPORTFLOW_KB_ADMIN_TEST_DSN'),reason='run scripts/test-customer-rls.py --with-ai')
IDS=count(24_000_001,20)
VECTOR=[1.0]+[0.0]*1023


@pytest.fixture
def corpus(monkeypatch):
    base=next(IDS);tenant='KP'+str(base);foreign='KQ'+str(base)
    states=[('draft',1,3,False),('review',2,3,False),('offline',4,3,False),('ready',3,3,False),
            ('unindexed',3,1,False),('indexing',3,2,False),('failed',3,4,False),('deleted',3,3,True)]
    docs={}
    with psycopg.connect(os.environ['SUPPORTFLOW_KB_ADMIN_TEST_DSN']) as db:
        for offset,(label,status,index_status,deleted) in enumerate(states+[('foreign',3,3,False)]):
            doc=base+offset;docs[label]=doc;owner=foreign if label=='foreign' else tenant
            db.execute("""INSERT INTO kb_document(id,tenant_code,doc_no,title,status,index_status,is_deleted)
                VALUES(%s,%s,%s,'synthetic published policy',%s,%s,%s)""",(doc,owner,'PUBLISH_'+str(doc),status,index_status,deleted))
            db.execute("""INSERT INTO kb_chunk(id,tenant_code,doc_id,chunk_no,content,embedding,embedding_model)
                VALUES(%s,%s,%s,0,'退款到账 published policy',%s::vector,'published-fixture')""",(doc,owner,doc,store.to_pgvector(VECTOR)))
    monkeypatch.setattr(store,'connect',lambda:psycopg.connect(os.environ['SUPPORTFLOW_KB_TEST_DSN'],row_factory=dict_row))
    return tenant,docs


def searches(tenant):
    return (lambda scope,policy:store.search(tenant,VECTOR,top_k=20,doc_ids=scope,published_only=policy),
            lambda scope,policy:store.keyword_search(tenant,'退款到账',top_k=20,doc_ids=scope,published_only=policy))


def test_real_vector_and_keyword_only_serve_published_ready_documents_to_visitors(corpus):
    tenant,docs=corpus
    for query in searches(tenant):
        assert {row['doc_id'] for row in query(None,True)}=={docs['ready']}
        # Staff's existing tenant preview remains possible; this is not a grant.
        preview={row['doc_id'] for row in query(None,False)}
        assert len(preview)==7 and docs['draft'] in preview and docs['foreign'] not in preview and docs['deleted'] not in preview
        assert query([docs['draft'],docs['foreign'],docs['deleted']],True)==[]
        assert {row['doc_id'] for row in query([docs['ready'],docs['draft']],True)}=={docs['ready']}
        assert query([],True)==[]


def test_next_query_reflects_offline_index_failure_and_republication_without_cache(corpus):
    tenant,docs=corpus
    for status,index_status,expected in [(3,3,{docs['ready']}),(4,3,set()),(3,4,set()),(3,2,set()),(3,3,{docs['ready']})]:
        with psycopg.connect(os.environ['SUPPORTFLOW_KB_ADMIN_TEST_DSN']) as db:
            db.execute('UPDATE kb_document SET status=%s,index_status=%s WHERE id=%s',(status,index_status,docs['ready']))
        for query in searches(tenant):assert {row['doc_id'] for row in query([docs['ready']],True)}==expected


@pytest.mark.parametrize('engine',['builtin','llama'])
@pytest.mark.parametrize('mode',['local','zero','miss'])
def test_real_pipeline_fallback_keeps_visitor_visibility_and_document_scope(corpus,monkeypatch,engine,mode):
    tenant,docs=corpus
    vectors,source=([VECTOR],'local-hash') if mode=='local' else ([[0.0]*1024],'fixture') if mode=='zero' else ([VECTOR],'fixture')
    monkeypatch.setattr(pipeline.llama_engine,'available',lambda:engine=='llama')
    monkeypatch.setattr(pipeline.embedding_module,'embed_texts',lambda *a,**k:(vectors,source))
    monkeypatch.setattr(llama_engine,'_embed_texts',lambda *a,**k:(vectors,source))
    scope=[docs['draft'],docs['foreign']] if mode=='miss' else [docs['ready'],docs['draft']]
    result=pipeline.search(tenant,'退款到账',doc_ids=scope,published_only=True)
    assert {row['doc_id'] for row in result['results']}==(set() if mode=='miss' else {docs['ready']})
    if mode!='miss':assert 'keyword' in result['mode']


@pytest.mark.parametrize('engine', ['linear', 'langgraph'])
@pytest.mark.parametrize('change', ['none', 'offline', 'index-failed', 'deleted', 'replaced'])
def test_publication_is_rechecked_after_model_response(corpus, monkeypatch, engine, change):
    if engine == 'langgraph' and not graph.langgraph_available():
        pytest.skip('Install LangGraph to exercise the graph path')
    tenant, docs = corpus
    monkeypatch.setattr(graph, 'langgraph_available', lambda: engine == 'langgraph')
    monkeypatch.setattr(graph, '_graph', None)
    settings = SimpleNamespace(rag_vector_min_score=.35, rag_keyword_min_score=.12, llm_timeout=1)
    monkeypatch.setattr(graph, 'get_settings', lambda: settings)
    monkeypatch.setattr(rag, 'get_settings', lambda: settings)
    monkeypatch.setattr(rag, 'resolve_provider', lambda tenant: ({'model': 'synthetic-model'}, 'fixture'))

    def retrieve(*, query, **scope):
        return {'mode': 'keyword', 'results': store.keyword_search(query='退款到账', **scope)}

    async def complete(_chosen, payload, *_args, **_kwargs):
        assert '退款到账 published policy' in payload['messages'][1]['content']
        changes = {'offline': 'UPDATE kb_document SET status=4 WHERE id=%s',
                   'index-failed': 'UPDATE kb_document SET index_status=4 WHERE id=%s',
                   'deleted': 'UPDATE kb_document SET is_deleted=TRUE WHERE id=%s',
                   'replaced': "UPDATE kb_chunk SET content='synthetic replacement' WHERE id=%s"}
        if change in changes:
            with psycopg.connect(os.environ['SUPPORTFLOW_KB_ADMIN_TEST_DSN']) as db:
                db.execute(changes[change], (docs['ready'],))
        return {'choices': [{'message': {'content': '合成初始材料回答[1]'}}]}

    monkeypatch.setattr(graph.retriever, 'retrieve_with_meta', retrieve)
    monkeypatch.setattr(rag, 'call_chat', complete)
    result = asyncio.run(graph.ask('退款到账', tenant, doc_ids=[docs['ready']], published_only=True))
    if change == 'none':
        assert result['decision'] == 'answer' and result['answer'] == '合成初始材料回答[1]'
        assert result['citations'][0]['doc_id'] == str(docs['ready'])
    else:
        assert result['decision'] == 'abstain' and result['abstention_reason'] == 'stale_evidence'
        assert not result['citations'] and not result['retrieved'] and '合成初始材料回答' not in result['answer']


def test_published_evidence_must_still_belong_to_the_same_tenant_and_document_scope(corpus):
    tenant, docs = corpus
    hits = store.keyword_search(tenant, '退款到账', doc_ids=[docs['ready']], published_only=True)
    assert store.published_evidence_is_current(tenant, hits, None)
    assert not store.published_evidence_is_current(tenant, hits, [docs['draft']])
    assert not store.published_evidence_is_current(tenant.replace('KP', 'KQ'), hits, None)
