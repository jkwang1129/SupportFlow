"""Fault injection against existing RAG nodes; no provider key or network required."""
import json

import httpx
import pytest

from ai.core import llm_chat
from ai.rag import graph
from ai.services import rag


HIT = {"id": 1, "doc_id": 101, "doc_title": "Synthetic refund policy", "chunk_no": 0,
       "score": .9, "content": "信用卡退款需要3至15个工作日到账。"}


def configure(monkeypatch, response):
    monkeypatch.setattr(graph.retriever, "retrieve_with_meta",
                        lambda **kw: {"results": [dict(HIT)], "mode": "vector"})
    monkeypatch.setattr(rag, "resolve_provider", lambda _: ({"provider": "fault-test", "api_key": "synthetic",
                        "model": "synthetic", "base_url": "http://fault.invalid/v1"}, "synthetic"))
    native_client = httpx.AsyncClient
    transport = httpx.MockTransport(response)
    monkeypatch.setattr(llm_chat.httpx, "AsyncClient",
                        lambda **kw: native_client(transport=transport, **kw))
    monkeypatch.setattr(llm_chat, "record_chat_usage", lambda **kw: None)


@pytest.mark.parametrize("scenario,error", [("http503", RuntimeError), ("timeout", httpx.ReadTimeout),
                                            ("empty", RuntimeError), ("malformed_json", json.JSONDecodeError)])
def test_generation_fault_propagates_without_a_fabricated_answer(monkeypatch, scenario, error):
    def response(request):
        if scenario == "timeout":
            raise httpx.ReadTimeout("Synthetic timeout", request=request)
        if scenario == "http503":
            return httpx.Response(503, json={"error": "synthetic outage"})
        if scenario == "malformed_json":
            return httpx.Response(200, text="not json")
        return httpx.Response(200, json={"choices": [{"message": {"content": ""}}]})
    configure(monkeypatch, response)
    import asyncio
    with pytest.raises(error):
        asyncio.run(graph.ask("信用卡退款多久到账？", "EVAL_A"))


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


@pytest.mark.xfail(strict=True, reason="Known gap: verify removes source metadata but leaves [99] in answer text")
def test_invalid_citation_is_removed_from_the_answer_text(monkeypatch):
    configure(monkeypatch, lambda request: httpx.Response(200, json={
        "choices": [{"message": {"content": "退款3至15个工作日到账[99]。"}}]}))
    import asyncio
    result = asyncio.run(graph.ask("退款多久到账？", "EVAL_A"))
    assert "[99]" not in result["answer"]


def test_in_range_index_does_not_prove_claim_support():
    # Current verification checks identifiers, not whether the statement is true.
    citations = [{"index": 1, "doc_id": "101", "content": HIT["content"]}]
    kept, dropped = rag.verify_citations("退款即时到账[1]", citations)
    assert kept == citations and dropped == []
