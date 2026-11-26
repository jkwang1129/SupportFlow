"""Registry + both signed SDKs + MODEL adapter, deterministic HTTP boundaries."""
import copy
from dataclasses import replace
import hashlib
import json
from threading import Event
from types import SimpleNamespace
from uuid import uuid4

import httpx
import pytest

from ai.runtime.embeddings import RuntimeEmbeddingClient
from ai.runtime.knowledge import KnowledgeClient, knowledge_digest
from ai.runtime.knowledge_tools import knowledge_search_tool, VERSION, KnowledgeToolResult
from ai.runtime.source import SourceClient
from ai.runtime.tools import ToolCall, ToolExecutor, ToolRegistry, ToolIdentity
from ai.runtime.worker import ExecutionContext, PermanentFailure, TransientFailure
from test_runtime_source import INPUT, RUN, KEY, envelope as source_envelope, response
from test_runtime_customer_tools import source_context
from test_runtime_embeddings import profile, answer

TEXT = 'synthetic query'


def execution_context():
    shim = source_context()
    row = shim.run | dict(owner=shim.lease.owner, fence=shim.lease.fence)
    ctx = ExecutionContext(SimpleNamespace(heartbeat=lambda *a, **k: None), row, Event(), 30)
    ctx.steps = shim.steps
    ctx.step = shim.step
    ctx.payload_step = shim.payload_step
    return ctx


def setup(*, context=None, model_profile=None, scopes=frozenset({'knowledge:read'}), state=None, counts=None):
    ctx = context or execution_context()
    state = {} if state is None else state
    counts = dict(source=0, embedding=0, knowledge=0) if counts is None else counts
    identity = ToolIdentity.from_context(ctx)
    trusted = identity.source
    source = source_envelope()
    value = source['data']
    payload = json.loads(value['payload_json'])
    payload['trusted_source'].update(tenant_code=trusted.tenant, source_event_id=str(trusted.source_event_id), run_id=str(identity.run_id),
                                     session_no=trusted.session_no, message_id=str(trusted.message_id), event_seq=trusted.event_seq, principal_ref=trusted.principal_ref)
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
    value.update(tenant_code=trusted.tenant, source_event_id=str(trusted.source_event_id), run_id=str(identity.run_id),
                 snapshot_id=state.setdefault('snapshot_id', str(uuid4())), payload_json=raw, payload_hash=hashlib.sha256(raw.encode()).hexdigest())

    def source_server(request):
        counts['source'] += 1
        assert request.method == 'POST' and request.url.path.endswith('/snapshot')
        if state.get('revoked'):
            return response(dict(code=40402, message='PRIVATE ERROR', data=None))
        return response(source)

    def embedding_server(request):
        counts['embedding'] += 1
        assert json.loads(request.content)['input'] == [TEXT]
        if state.get('embedding_hook'):
            state['embedding_hook']()
        return response(answer(), status=state.get('model_status', 200))

    def knowledge_server(request):
        counts['knowledge'] += 1
        body = json.loads(request.content)
        assert set(body) == {'snapshot_id', 'query_sha256', 'embedding_model', 'vector', 'top_k', 'doc_ids'}
        value = dict(schema_version='knowledge-search-v1', policy_version='visitor-published-v1', tenant_code=trusted.tenant,
                     source_event_id=str(trusted.source_event_id), run_id=str(identity.run_id), snapshot_id=body['snapshot_id'], source_hash=source['data']['payload_hash'],
                     request_sha256=hashlib.sha256(request.content).hexdigest(), query_sha256=body['query_sha256'], embedding_model=body['embedding_model'], hits=[])
        if not state.get('offline'):
            value['hits'] = [dict(doc_id='42', chunk_id='43', title='synthetic title',
                                  snippet='synthetic knowledge snippet', score_ppm=800000, embedding_model=body['embedding_model'])]
        if state.get('changed_source'):
            value['source_hash'] = 'f' * 64
        value['resource_version'] = knowledge_digest(value)
        return response(dict(code=0, message='成功', data=value))
    source_client = SourceClient('http://customer.test', KEY, transport=httpx.MockTransport(source_server))
    kb = KnowledgeClient('http://customer.test', KEY, transport=httpx.MockTransport(knowledge_server))
    embedding = RuntimeEmbeddingClient(model_profile or profile(), transport=httpx.MockTransport(embedding_server))
    tool = knowledge_search_tool(ctx, source_client, kb, embedding)
    return ctx, ToolExecutor(ToolRegistry([tool]), scopes), counts, state, tool


def call(**changes): return ToolCall('search_knowledge', VERSION, dict(query=TEXT) | changes)


def test_registered_tool_separates_current_source_read_query_model_and_knowledge_read():
    ctx, executor, counts, _, tool = setup()
    value = executor.execute(ctx, 'knowledge-1', call())
    assert value['mode'] == 'vector' and value['hits'][0]['doc_id'] == '42'
    assert counts == dict(source=1, embedding=1, knowledge=1)
    assert [(item[1], item[2]) for item in ctx.steps] == [('INTERNAL', 'NONE'),
                                                          ('TOOL', 'READ'), ('INTERNAL', 'NONE'), ('TOOL', 'READ'), ('MODEL', 'NONE')]
    assert TEXT not in str(ctx.steps) and 'synthetic knowledge snippet' not in repr(KnowledgeToolResult(**value))
    assert tool.cache_policy == 'current-version' and not {'tenant_code', 'vector', 'query'}.intersection(value)
    catalog = executor.registry.catalog()[0]
    assert set(catalog['arguments']['properties']) == {
        'query', 'top_k', 'doc_ids'} and catalog['effect'] == 'READ' and catalog['parallel'] is False


@pytest.mark.parametrize('arguments', [dict(tenant_code='TENANT_B'), dict(url='http://outside'), dict(vector=[.25]),
                                       dict(embedding_model='other:model'), dict(snapshot_id=str(uuid4())), dict(
                                           published_only=False), dict(query=' '), dict(query='中' * 1366),
                                       dict(top_k=True), dict(top_k=0), dict(top_k=6), dict(doc_ids=[True]), dict(doc_ids=['42']), dict(doc_ids=[0]), dict(doc_ids=list(range(1, 202)))])
def test_model_cannot_override_authority_or_send_invalid_query(arguments):
    ctx, executor, counts, _, _ = setup()
    with pytest.raises(PermanentFailure, match='^TOOL_ARGUMENTS_INVALID$'):
        executor.execute(ctx, 'knowledge-1', call(**arguments))
    assert counts == dict(source=0, embedding=0, knowledge=0) and ctx.steps == []


def test_missing_host_scope_stops_before_any_runtime_step_or_http():
    ctx, executor, counts, _, _ = setup(scopes=frozenset())
    with pytest.raises(PermanentFailure, match='^TOOL_SCOPE_DENIED$'):
        executor.execute(ctx, 'knowledge-1', call())
    assert counts == dict(source=0, embedding=0, knowledge=0) and ctx.steps == []


def test_revoked_source_stops_before_embedding_and_knowledge_requests():
    ctx, executor, counts, _, _ = setup(state=dict(revoked=True))
    with pytest.raises(PermanentFailure, match='^SOURCE_REVOKED$'):
        executor.execute(ctx, 'knowledge-1', call())
    assert counts == dict(source=1, embedding=0, knowledge=0)


def test_empty_scope_has_no_fake_vector_request_hash_version_or_model_usage():
    ctx, executor, counts, _, _ = setup()
    value = executor.execute(ctx, 'knowledge-1', call(doc_ids=[]))
    assert value['mode'] == 'empty-scope' and value['hits'] == [
    ] and value['request_sha256'] is None and value['resource_version'] is None
    assert counts == dict(source=1, embedding=0, knowledge=0) and len(ctx.steps) == 4


def test_a_second_access_queries_live_knowledge_but_reuses_committed_query_representation():
    ctx, executor, counts, state, _ = setup()
    cached = {}
    original = ctx.payload_step

    def payload(key, *arguments):
        if key not in cached:
            cached[key] = original(key, *arguments)
        return copy.deepcopy(cached[key])
    ctx.payload_step = payload
    assert executor.execute(ctx, 'knowledge-1', call())['hits']
    state['offline'] = True
    assert executor.execute(ctx, 'knowledge-1', call())['hits'] == []
    assert counts == dict(source=2, embedding=1, knowledge=2) and len(cached) == 3


def test_source_hash_changed_after_authorization_cannot_become_a_verified_tool_result():
    ctx, executor, counts, _, _ = setup(state=dict(changed_source=True))
    with pytest.raises(PermanentFailure, match='^KNOWLEDGE_PAYLOAD_INVALID$'):
        executor.execute(ctx, 'knowledge-1', call())
    assert counts == dict(source=1, embedding=1, knowledge=1)


def test_model_503_has_no_local_retry_or_knowledge_fallback():
    ctx, executor, counts, _, _ = setup(state=dict(model_status=503))
    with pytest.raises(TransientFailure, match='^MODEL_UNAVAILABLE$'):
        executor.execute(ctx, 'knowledge-1', call())
    assert counts == dict(source=1, embedding=1, knowledge=0)


@pytest.mark.parametrize('text', [KEY, profile().api_key])
def test_current_configured_credentials_are_rejected_before_source_or_model_http(text):
    ctx, executor, counts, _, _ = setup()
    with pytest.raises(PermanentFailure, match='^EMBEDDING_INPUT_INVALID$'):
        executor.execute(ctx, 'knowledge-1', call(query=text))
    assert counts == dict(source=0, embedding=0, knowledge=0) and text not in str(ctx.steps)


def test_per_run_tool_cannot_be_executed_in_a_different_run_context():
    ctx, executor, counts, _, _ = setup()
    foreign = source_context()
    foreign.run['run_id'] = uuid4()
    foreign.lease = replace(foreign.lease, run_id=foreign.run['run_id'])
    with pytest.raises(PermanentFailure, match='^TOOL_IDENTITY_INVALID$'):
        executor.execute(foreign, 'knowledge-1', call())
    assert counts == dict(source=0, embedding=0, knowledge=0)


def test_factory_requires_real_context_matching_service_clients_and_compatible_tenant_profile():
    ctx = execution_context()
    source = SourceClient('http://customer.test', KEY)
    kb = KnowledgeClient('http://customer.test', KEY)
    embedding = RuntimeEmbeddingClient(profile())
    for values in [(object(), source, kb, embedding), (ctx, source, KnowledgeClient('http://other.test', KEY), embedding),
                   (ctx, source, KnowledgeClient('http://customer.test', 'other-pair-' + '0' * 40), embedding),
                   (ctx, source, kb, object())]:
        with pytest.raises(ValueError):
            knowledge_search_tool(*values)
    with pytest.raises(PermanentFailure, match='^MODEL_PROFILE_DENIED$'):
        knowledge_search_tool(ctx, source, kb, RuntimeEmbeddingClient(profile(tenant='TENANT_B')))
