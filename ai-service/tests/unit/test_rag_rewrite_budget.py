"""Verify the optional rewrite budget without altering the frozen abstention contract."""
import asyncio
from types import SimpleNamespace

import pytest

from ai.rag import graph
from ai.services import rag


GRAPH_INSTALLED = graph.langgraph_available()


@pytest.mark.parametrize("limit", [True, -1, 2, 0.0, "0", None])
def test_invalid_rewrite_limit_is_rejected_before_retrieval(monkeypatch, limit):
    monkeypatch.setattr(graph.retriever, "retrieve_with_meta", lambda **_: pytest.fail("unexpected retrieval"))
    with pytest.raises(ValueError, match="max_rewrite_rounds"):
        asyncio.run(graph.ask("历史订单能否退货", "CONTRACT_A", max_rewrite_rounds=limit))


@pytest.mark.parametrize("engine", ["linear", "langgraph"])
def test_single_pass_shares_the_publication_and_citation_contract(monkeypatch, engine):
    if engine == "langgraph" and not GRAPH_INSTALLED:
        pytest.skip("Install requirements-test.txt to exercise the actual LangGraph path")
    monkeypatch.setattr(graph, "langgraph_available", lambda: engine == "langgraph")
    monkeypatch.setattr(graph, "_graph", None)
    monkeypatch.setattr(graph, "get_settings", lambda: SimpleNamespace(rag_keyword_min_score=0.12))
    searches = []
    verifies = []

    def retrieve(**request):
        searches.append(request)
        score = 0.1 if request["query"] == "历史订单能否退货" else 0.8
        return {"mode": "keyword", "results": [{"doc_id": 101, "score": score}]}

    def verify_sources(tenant, hits, scope):
        verifies.append((tenant, scope, hits[0]["doc_id"]))
        return True

    async def generate(**_):
        return rag.ComposedAnswer("合成答复[1]", [{"index": 1}], True)

    monkeypatch.setattr(graph.retriever, "retrieve_with_meta", retrieve)
    monkeypatch.setattr(graph.store, "published_evidence_is_current", verify_sources)
    monkeypatch.setattr(rag, "compose_answer_details", generate)
    args = ("历史订单能否退货", "CONTRACT_A", 5, [101])
    single = asyncio.run(graph.ask(*args, published_only=True, max_rewrite_rounds=0))
    agentic = asyncio.run(graph.ask(*args, published_only=True))

    assert [step["node"] for step in single["steps"]] == ["retrieve", "grade", "abstain"]
    assert [step["node"] for step in agentic["steps"]] == [
        "retrieve", "grade", "rewrite", "retrieve", "grade", "answer", "verify"]
    assert single["decision"] == "abstain" and single["citations"] == []
    assert agentic["decision"] == "answer" and agentic["citations"] == [{"index": 1}]
    assert searches[0] == searches[1]
    assert all(request["published_only"] and request["doc_ids"] == [101] for request in searches)
    assert verifies == [("CONTRACT_A", [101], 101)] * 2
