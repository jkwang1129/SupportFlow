"""Batch transport and the legacy RAG boundaries use synthetic input and vectors."""

import asyncio
import json
import logging
from types import SimpleNamespace
from uuid import uuid4

import httpx
import pytest

from ai.agents import brain as brain_agent
from ai.core import chat_transport
from ai.core.trace import get_trace_id
from ai.rag import embedding, graph, llama_engine, pipeline, store
from ai.services import brain, training
from test_chat_transport import local_provider
from test_internal_auth import client, signed


TENANT = "TENANT_A"
KEY = "fixture-only-embedding-key"
MODEL = "text-embedding-3-small"
TEXT = "private-fixture refund instruction"
KB = "/api/ai/v1/kb"
DIM = embedding.EMBEDDING_DIM


@pytest.fixture
def settings(monkeypatch):
    result = SimpleNamespace(embedding_base_url="http://fixture-model.test/v1", embedding_model=MODEL,
                             embedding_timeout=1, embedding_provider="fixture", embedding_key=lambda: KEY)
    monkeypatch.setattr(embedding, "get_settings", lambda: result)
    return result


def answer(indices):
    # Use high-precision numbers to exercise the response limit with a full ten-vector batch.
    return {"data": [{"index": index, "embedding": [float(index + 1)] + [-0.012345678901234567] * (DIM - 1)}
                     for index in indices]}


def install_transport(monkeypatch, handler):
    clients = []

    def create(timeout):
        client = chat_transport._model_client(timeout, transport=httpx.MockTransport(handler))
        clients.append(client)
        return client

    monkeypatch.setattr(embedding, "_model_client", create)
    return clients


def response(value, status=200):
    body = value if isinstance(value, bytes) else json.dumps(value).encode()
    return httpx.Response(status, headers={"Content-Type": "application/json"}, stream=httpx.ByteStream(body))


def signed_request(method, path):
    return {**signed(method, path, tenant=TENANT), "X-Request-Id": "embedding-fixture-trace"}


def test_full_remote_batches_preserve_item_order_and_reuse_one_client(monkeypatch, settings, caplog):
    caplog.set_level(logging.INFO)
    payloads = []

    def provider(request):
        assert request.url == settings.embedding_base_url + "/embeddings"
        assert request.headers["Authorization"] == "Bearer " + KEY
        assert request.headers["Accept-Encoding"] == "identity"
        body = json.loads(request.content)
        assert body["dimensions"] == DIM and body["model"] == MODEL
        payloads.append(body)
        return response(answer(reversed(range(len(body["input"])))))

    clients = install_transport(monkeypatch, provider)
    texts = [f"{TEXT} {index}" for index in range(23)]
    vectors, source = embedding.embed_texts(texts, tenant_code=TENANT, trace_id="embedding-fixture-trace")
    assert source == f"fixture:{MODEL}"
    assert [len(record["input"]) for record in payloads] == [10, 10, 3]
    assert [text for payload in payloads for text in payload["input"]] == texts
    assert [vector[0] for vector in vectors] == [float(index % 10 + 1) for index in range(23)]
    assert len(clients) == 1 and clients[0].is_closed
    assert TEXT not in caplog.text and KEY not in caplog.text


def test_failed_later_batch_discards_partial_remote_embeddings(monkeypatch, settings, caplog):
    attempts = []

    def provider(request):
        attempts.append(json.loads(request.content))
        if len(attempts) == 1:
            return response(answer(range(10)))
        return response(b"private-fixture-provider-error", 429)

    install_transport(monkeypatch, provider)
    texts = [f"{TEXT} {index}" for index in range(23)]
    vectors, source = embedding.embed_texts(texts, tenant_code=TENANT)
    assert source == "local-hash"
    assert vectors == [embedding._local_vector(text) for text in texts]
    assert len(attempts) == 2
    assert "MODEL_RATE_LIMITED" in caplog.text and "private-fixture-provider-error" not in caplog.text


@pytest.mark.parametrize("mode, code", [("slow", "MODEL_TIMEOUT"), ("redirect", "MODEL_PROTOCOL_ERROR")])
def test_loopback_timeout_and_redirect_fall_back_after_one_attempt(settings, monkeypatch, caplog, mode, code):
    monkeypatch.setenv("HTTP_PROXY", "http://127.0.0.1:1")
    monkeypatch.setenv("ALL_PROXY", "http://127.0.0.1:1")
    monkeypatch.setenv("NO_PROXY", "")
    with local_provider(mode, response_body=answer([0])) as (base, attempts):
        settings.embedding_base_url = base
        settings.embedding_timeout = .15 if mode == "slow" else 2
        assert embedding.embed_texts([TEXT])[1] == "local-hash"
    assert len(attempts) == 1
    assert attempts[0][0:2] == ("/v1/embeddings", "Bearer " + KEY)
    assert code in caplog.text


@pytest.mark.parametrize("case", ["duplicate", "missing", "out-of-range", "boolean-index", "no-index",
                                  "wrong-size", "non-number", "infinity", "oversized-number"])
def test_invalid_provider_batch_is_never_returned_to_indexer(case, monkeypatch, settings, caplog):
    result = answer([0, 1])
    data = result["data"]
    if case == "duplicate":
        data[1]["index"] = 0
    elif case == "missing":
        data.pop()
    elif case == "out-of-range":
        data[1]["index"] = 2
    elif case == "boolean-index":
        data[1]["index"] = True
    elif case == "no-index":
        del data[1]["index"]
    elif case == "wrong-size":
        data[1]["embedding"].pop()
    else:
        data[1]["embedding"][0] = {"non-number": "1.0", "infinity": float("inf"),
                                    "oversized-number": 1e38}[case]

    calls = []

    def provider(request):
        calls.append(request)
        if case == "infinity":
            # Valid JSON number syntax that would decode to a non-finite float.
            return response(json.dumps(result).replace("Infinity", "1e999").encode())
        return response(result)

    install_transport(monkeypatch, provider)
    assert embedding.embed_texts([TEXT, "second"])[1] == "local-hash"
    assert len(calls) == 1 and "EMBEDDING_OUTPUT_INVALID" in caplog.text


@pytest.mark.parametrize("side", ["request", "response"])
def test_request_and_response_byte_limits_fail_before_using_any_vectors(side, monkeypatch, settings, caplog):
    calls = []

    def provider(request):
        calls.append(request)
        return response(b" " * (chat_transport.MAX_CHAT_BYTES + 1))

    install_transport(monkeypatch, provider)
    text = "x" * (chat_transport.MAX_CHAT_BYTES + 1) if side == "request" else TEXT
    assert embedding.embed_texts([text])[1] == "local-hash"
    assert len(calls) == (0 if side == "request" else 1)
    assert ("MODEL_REQUEST_INVALID" if side == "request" else "MODEL_RESPONSE_TOO_LARGE") in caplog.text


@pytest.mark.parametrize("engine", [False, True], ids=["builtin", "llama"])
@pytest.mark.parametrize("status", [200, 503])
def test_signed_search_uses_bounded_embedding_and_preserves_scope(engine, status, client, monkeypatch, settings):
    monkeypatch.setattr(llama_engine, "available", lambda: engine)
    observations = []

    def record(mode):
        def search(tenant, query_or_vector, *, doc_ids, top_k, published_only):
            observations.append((mode, tenant, doc_ids, top_k, published_only, get_trace_id()))
            return [{"doc_id": 101, "content": "test evidence", "score": 1.0}]
        return search

    monkeypatch.setattr(store, "search", record("vector"))
    monkeypatch.setattr(store, "keyword_search", record("keyword"))
    path = KB + "/search"
    with local_provider("normal", response_body=answer([0]), status=status) as (base, attempts):
        settings.embedding_base_url = base
        result = client.post(path, json={"tenantCode": TENANT, "query": TEXT, "docIds": [101], "topK": 3},
                             headers=signed_request("POST", path))
    assert result.status_code == 200 and len(attempts) == 1
    body = result.json()
    assert body["vector_source"] == (f"fixture:{MODEL}" if status == 200 else "local-hash")
    assert body["results"][0]["doc_id"] == 101
    assert observations == [("vector" if status == 200 else "keyword", TENANT,
                             [101], 3, False, "embedding-fixture-trace")]


@pytest.mark.parametrize("path", [KB + "/index", KB + "/index-text", KB + "/stage-index", KB + "/stage-index-text"])
def test_signed_index_dispatches_embedding_and_stores_source(path, client, monkeypatch, settings):
    monkeypatch.setattr(llama_engine, "available", lambda: False)
    saved = []
    monkeypatch.setattr(store, "replace_chunks", lambda *values, attempt_id: saved.append((values, attempt_id)) or len(values[2]))
    params = {"tenant_code": TENANT}
    attempt = uuid4() if '/stage-index' in path else None
    if attempt:
        params['attempt_id'] = str(attempt)
    kwargs = {"json": {"docId": 101, "content": TEXT}}
    if not path.endswith("-text"):
        params.update(doc_id=101, file_name="fixture.txt")
        kwargs = {"content": TEXT.encode()}
    with local_provider("normal", response_body=answer([0])) as (base, attempts):
        settings.embedding_base_url = base
        result = client.post(path, params=params, headers=signed_request("POST", path), **kwargs)
    assert result.status_code == 200 and len(attempts) == len(saved) == 1
    (tenant, doc_id, chunks, vectors, source), received_attempt = saved[0]
    assert received_attempt == attempt
    assert (tenant, doc_id, source) == (TENANT, 101, f"fixture:{MODEL}")
    assert len(chunks) == len(vectors) == 1 and len(vectors[0]) == DIM


@pytest.mark.parametrize('attempt', [None, 'invalid-attempt'])
def test_staged_index_rejects_missing_or_invalid_attempt_before_indexing(client, monkeypatch, attempt):
    path = KB + '/stage-index-text'
    params = {'tenant_code': TENANT}
    if attempt is not None:
        params['attempt_id'] = attempt
    monkeypatch.setattr(pipeline, 'index_text', lambda **_: pytest.fail('Invalid attempt reached the indexer'))
    response = client.post(path, params=params, json={'docId': 101, 'content': TEXT}, headers=signed_request('POST', path))
    assert response.status_code == 422


def test_explicit_local_source_applies_in_llama_mode(monkeypatch, settings):
    def unexpected(*_, **__):
        pytest.fail("local-only configuration dispatched a model")

    settings.embedding_provider = "local"
    monkeypatch.setattr(embedding, "_model_client", unexpected)
    vectors, source = llama_engine._embed_texts([TEXT], TENANT)
    assert source == "local-hash" and len(vectors[0]) == DIM


@pytest.mark.parametrize("use_graph", [False, True], ids=["linear", "langgraph"])
def test_rag_retrieval_runs_bound_embedding_off_event_loop(monkeypatch, settings, use_graph):
    if use_graph and not graph.langgraph_available():
        pytest.skip("requires optional LangGraph")
    monkeypatch.setattr(graph, "langgraph_available", lambda: use_graph)
    monkeypatch.setattr(graph, "_graph", None)
    monkeypatch.setattr(llama_engine, "available", lambda: False)
    monkeypatch.setattr(store, "search", lambda *_, **__: [])
    monkeypatch.setattr(store, "keyword_search", lambda *_, **__: [])
    with local_provider("normal", response_body=answer([0])) as (base, attempts):
        settings.embedding_base_url = base
        result = asyncio.run(graph.ask(TEXT, TENANT, max_rewrite_rounds=0))
    assert result["decision"] == "abstain" and len(attempts) == 1
    assert attempts[0][2]["input"] == [TEXT]


def test_brain_trained_intent_keeps_remote_vectors_working_off_event_loop(monkeypatch, settings):
    intent = {"intent_code": "FIXTURE", "name": "fixture"}
    monkeypatch.setattr(brain, "load_setting", lambda _: brain.DEFAULT_SETTING)
    monkeypatch.setattr(brain, "load_intents", lambda _: [intent])
    monkeypatch.setattr(brain, "load_dialogue", lambda *_: {})
    monkeypatch.setattr(brain, "resolve_provider", lambda _: (None, "fixture"))
    monkeypatch.setattr(brain, "get_settings_config", lambda: SimpleNamespace(train_match_min_score=.5))
    monkeypatch.setattr(training, "resolve_version", lambda *_: "fixture-v1")
    observed = []

    class Connection:
        def __enter__(self):
            return self

        def __exit__(self, *_):
            pass

        def execute(self, statement, args):
            observed.append(args)
            return self

        def fetchone(self):
            return {"intent_code": "FIXTURE", "intent_name": "fixture", "similarity": .95, "sample": "test"}

    monkeypatch.setattr(training, "connect", Connection)
    with local_provider("normal", response_body=answer([0])) as (base, attempts):
        settings.embedding_base_url = base
        result = asyncio.run(brain_agent.node_recognize({"tenant_code": TENANT, "question": TEXT}))
    assert len(attempts) == len(observed) == 1
    assert observed[0][1:3] == (TENANT, "fixture-v1")
    assert result["intent"] == intent and result["steps"][-1]["source"] == "trained"
