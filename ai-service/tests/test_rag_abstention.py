"""Small versioned routing contract set; generation is stubbed, no online quality claims."""
import asyncio
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from ai.rag import graph
from ai.services import rag


DATASET = json.loads((Path(__file__).resolve().parents[2] / "fixtures/evaluation/rag-abstention-v1.json").read_text(encoding="utf-8"))
GRAPH_INSTALLED = graph.langgraph_available()


@pytest.mark.parametrize("engine", ["linear", "langgraph"])
@pytest.mark.parametrize("case", DATASET["cases"], ids=lambda case: case["id"])
def test_routing_never_generates_after_the_evidence_budget_is_exhausted(monkeypatch, engine, case):
    if engine == "langgraph" and not GRAPH_INSTALLED:
        pytest.skip("Install requirements-test.txt to exercise the actual LangGraph path")
    monkeypatch.setattr(graph, "langgraph_available", lambda: engine == "langgraph")
    monkeypatch.setattr(graph, "_graph", None)
    monkeypatch.setattr(graph, "get_trace_id", lambda: "contract-trace")
    # Keep contract inputs independent of a developer's threshold environment overrides.
    monkeypatch.setattr(graph, "get_settings", lambda: SimpleNamespace(
        rag_vector_min_score=0.35, rag_keyword_min_score=0.12))
    searches, generations = [], []

    def retrieve(**kwargs):
        assert kwargs["tenant_code"] == "CONTRACT_A"
        assert kwargs["doc_ids"] == [101]
        scores = case.get("rewrite_scores", case["scores"]) if searches else case["scores"]
        searches.append(kwargs)
        return {"mode": case["mode"], "results": [
            {"id": i + 1, "doc_id": 101, "score": score, "content": "合成测试资料"}
            for i, score in enumerate(scores)]}

    async def generate(**kwargs):
        generations.append(kwargs)
        return "合成测试回答[1]", [{"index": 1, "doc_id": "101", "content": "合成测试资料"}]

    monkeypatch.setattr(graph.retriever, "retrieve_with_meta", retrieve)
    monkeypatch.setattr(rag, "compose_answer", generate)
    result = asyncio.run(graph.ask("合成测试问题", "CONTRACT_A", doc_ids=[101]))
    assert len(generations) == int(case["generate"])
    assert result["enough"] is case["generate"]
    assert result["decision"] == ("answer" if case["generate"] else "abstain")
    assert result["trace_id"] == "contract-trace"
    assert [step["sequence"] for step in result["steps"]] == list(range(1, len(result["steps"]) + 1))
    assert all(step["traceId"] == "contract-trace" and step["schemaVersion"] == 1 for step in result["steps"])
    assert len(searches) == (1 if case["generate"] and "rewrite_scores" not in case else 2)
    assert sum(step["node"] == "rewrite" for step in result["steps"]) <= 1
    if not case["generate"]:
        assert not result["citations"]
        assert "资料不足" in result["answer"]
        assert result["steps"][-1]["node"] == "abstain"
        assert result["abstention_reason"] == ("retrieval_unavailable" if case["mode"] == "error" else "insufficient_evidence")
    else:
        assert result["citations"]
        assert result["steps"][-1]["node"] == "verify"
