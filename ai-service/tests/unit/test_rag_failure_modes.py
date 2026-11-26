"""Fault injection against existing RAG nodes; no provider key or network required."""

import asyncio

import httpx
import pytest

from ai.core import llm_chat
from ai.core.chat_transport import ModelCallFailure
from ai.rag import graph
from ai.services import rag


HIT = {"id": 1, "doc_id": 101, "doc_title": "Synthetic refund policy", "chunk_no": 0,
       "score": .9, "content": "信用卡退款需要3至15个工作日到账。"}


def configure(monkeypatch, response):
    monkeypatch.setattr(graph.retriever, "retrieve_with_meta",
                        lambda **kw: {"results": [dict(HIT)], "mode": "vector"})
    monkeypatch.setattr(rag, "resolve_provider", lambda _: ({"provider": "fault-test", "api_key": "synthetic",
                        "model": "synthetic", "base_url": "http://fault.invalid/v1"}, "synthetic"))

    def server(request):
        result = response(request)
        # Model the unconsumed stream returned by an actual HTTP exchange.
        if result.is_stream_consumed:
            return httpx.Response(result.status_code, headers=result.headers, stream=httpx.ByteStream(result.content))
        return result
    transport = httpx.MockTransport(server)

    async def chat(chosen, payload, timeout, **kwargs):
        return await llm_chat.call_chat(chosen, payload, timeout, _transport=transport, **kwargs)
    monkeypatch.setattr(rag, "call_chat", chat)
    monkeypatch.setattr(llm_chat, "record_chat_usage", lambda **kw: None)


@pytest.mark.parametrize("scenario,error,code", [("http503", ModelCallFailure, 'MODEL_UNAVAILABLE'), ("timeout", ModelCallFailure, 'MODEL_TIMEOUT'),
                                                 ("empty", RuntimeError, None), ("malformed_json", ModelCallFailure, 'MODEL_RESPONSE_INVALID')])
def test_generation_fault_propagates_without_a_fabricated_answer(monkeypatch, scenario, error, code):
    def response(request):
        if scenario == "timeout":
            raise httpx.ReadTimeout("Synthetic timeout", request=request)
        if scenario == "http503":
            return httpx.Response(503, json={"error": "synthetic outage"})
        if scenario == "malformed_json":
            return httpx.Response(200, headers={'Content-Type': 'application/json'}, content=b'not json')
        return httpx.Response(200, json={"choices": [{"message": {"content": ""}}]})
    configure(monkeypatch, response)
    import asyncio
    with pytest.raises(error) as caught:
        asyncio.run(graph.ask("信用卡退款多久到账？", "EVAL_A"))
    if code:
        assert caught.value.code == code
    else:
        assert not isinstance(caught.value, ModelCallFailure)


def test_no_evidence_has_one_bounded_rewrite_and_no_model_call(monkeypatch):
    searches = []

    def search(**kw):
        searches.append(kw)
        return {"results": [], "mode": "vector"}
    monkeypatch.setattr(graph.retriever, "retrieve_with_meta", search)

    async def unexpected(*args, **kwargs):
        pytest.fail("No evidence must not invoke generation")
    monkeypatch.setattr(rag, "call_chat", unexpected)
    import asyncio
    result = asyncio.run(graph.ask("南极配送是否可达？", "EVAL_A"))
    assert len(searches) == 2
    assert sum(s["node"] == "rewrite" for s in result["steps"]) == 1
    assert not result["citations"] and not result["enough"]


@pytest.mark.parametrize('engine', ['linear', 'langgraph'])
@pytest.mark.parametrize('marker', ['99', '100', '９９', '9' * 30],
                         ids=['two_digit', 'three_digit', 'unicode_digits', 'oversized'])
def test_invalid_citation_withholds_generated_answer(monkeypatch, engine, marker):
    if engine == 'langgraph' and not graph.langgraph_available():
        pytest.skip('requires test LangGraph dependency')
    monkeypatch.setattr(graph, 'langgraph_available', lambda: engine == 'langgraph')
    monkeypatch.setattr(graph, '_graph', None)
    configure(monkeypatch, lambda request: httpx.Response(200, json={
        "choices": [{"message": {"content": f"已核对[1]。未支持的口径[{marker}]。"}}]}))
    result = asyncio.run(graph.ask("退款多久到账？", "EVAL_A"))
    assert result["decision"] == "abstain" and result["abstention_reason"] == "invalid_citation"
    assert not result["citations"] and f"[{marker}]" not in result["answer"] and "未支持的口径" not in result["answer"]
    assert result["steps"][-1]["node"] == "verify"


def test_generated_in_range_citation_is_preserved(monkeypatch):
    configure(monkeypatch, lambda request: httpx.Response(200, json={
        "choices": [{"message": {"content": "信用卡退款需要3至15个工作日到账[1]。"}}]}))
    result = asyncio.run(graph.ask("退款多久到账？", "EVAL_A"))
    assert result["decision"] == "answer" and result["abstention_reason"] is None
    assert "[1]" in result["answer"] and [entry["index"] for entry in result["citations"]] == [1]


def test_no_model_excerpt_keeps_literal_document_number_and_original_source(monkeypatch):
    monkeypatch.setattr(graph.retriever, "retrieve_with_meta",
                        lambda **kw: {"results": [dict(HIT, content="参见原文段落[99]。")], "mode": "vector"})
    monkeypatch.setattr(rag, "resolve_provider", lambda _: (None, "fixture"))
    result = asyncio.run(graph.ask("退款多久到账？", "EVAL_A"))
    assert result["decision"] == "answer" and result["abstention_reason"] is None
    assert "参见原文段落[99]。" in result["answer"] and len(result["citations"]) == 1


def test_in_range_index_does_not_prove_claim_support():
    # Current verification checks identifiers, not whether the statement is true.
    citations = [{"index": 1, "doc_id": "101", "content": HIT["content"]}]
    kept, dropped = rag.verify_citations("退款即时到账[1]", citations)
    assert kept == citations and dropped == []
