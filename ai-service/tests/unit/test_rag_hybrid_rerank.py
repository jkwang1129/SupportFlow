"""Unit tests for RAG 2.0 hybrid RRF search, Qwen/lexical reranker, and source authority governance."""

from __future__ import annotations

import asyncio
import json
from types import SimpleNamespace

import httpx
import pytest

from ai.rag import graph, grounded_reply, llama_engine, pipeline, reranker, retriever, store
from ai.services import rag


def _unexpected(*_args, **_kwargs):
    raise AssertionError("Unexpected I/O or model invocation")


def test_hybrid_search_rrf_fuses_and_deduplicates_chunks(monkeypatch):
    vec_calls = []
    kw_calls = []

    def fake_vector(tenant_code, vector, top_k=5, doc_ids=None, *, published_only=False):
        vec_calls.append((tenant_code, top_k, doc_ids, published_only))
        return [
            {"id": 10, "doc_id": 100, "chunk_no": 1, "content": "SLA P0 15分钟响应", "doc_title": "SLA", "score": 0.82},
            {"id": 20, "doc_id": 200, "chunk_no": 1, "content": "普通咨询排期", "doc_title": "指南", "score": 0.75},
        ]

    def fake_keyword(tenant_code, query, top_k=5, doc_ids=None, *, published_only=False):
        kw_calls.append((tenant_code, query, top_k, doc_ids, published_only))
        return [
            {"id": 30, "doc_id": 300, "chunk_no": 2, "content": "RMA 返修编码规则", "doc_title": "RMA", "score": 0.66},
            # Same (doc_id, chunk_no) as id=10 -> must deduplicate and fuse RRF score
            {"id": 10, "doc_id": 100, "chunk_no": 1, "content": "SLA P0 15分钟响应", "doc_title": "SLA", "score": 0.50},
        ]

    monkeypatch.setattr(store, "search", fake_vector)
    monkeypatch.setattr(store, "keyword_search", fake_keyword)

    results = store.hybrid_search(
        "TENANT_A",
        "SLA 15分钟",
        [0.5, 0.5],
        top_k=5,
        candidate_depth=12,
        rrf_k=60,
        doc_ids=[100, 200, 300],
        published_only=True,
    )
    assert vec_calls == [("TENANT_A", 12, [100, 200, 300], True)]
    assert kw_calls == [("TENANT_A", "SLA 15分钟", 12, [100, 200, 300], True)]
    # id=10 appears in both legs (1/61 + 1/62); id=30 is keyword rank 1 (1/61) > id=20 vector rank 2 (1/62)
    assert [row["id"] for row in results] == [10, 30, 20]
    expected_fused = round(1.0 / 61 + 1.0 / 62, 6)
    assert results[0]["rrf_score"] == expected_fused
    assert results[0]["score"] == expected_fused
    assert results[0]["vector_score"] == 0.82
    assert results[0]["keyword_score"] == 0.50
    assert results[0]["source_authority"] == "official"


def test_hybrid_search_validates_scope_and_visibility_before_io(monkeypatch):
    monkeypatch.setattr(store, "connect", _unexpected)
    assert store.hybrid_search("TENANT_A", "SLA", [1.0], doc_ids=[]) == []
    with pytest.raises(ValueError):
        store.hybrid_search("TENANT_A", "SLA", [1.0], doc_ids=[-5])
    with pytest.raises(ValueError):
        store.hybrid_search("TENANT_A", "SLA", [1.0], published_only="yes")  # type: ignore[arg-type]


def test_qwen_reranker_parses_scores_and_token_usage(monkeypatch):
    settings = SimpleNamespace(
        rag_rerank_mode="qwen",
        rag_rerank_url="https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank",
        rag_rerank_model="gte-rerank",
        rag_rerank_timeout=5.0,
        rag_rerank_max_response_bytes=65536,
        rerank_key=lambda: "sk-test-rerank",
    )
    monkeypatch.setattr(reranker, "get_settings", lambda: settings)

    seen_requests = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen_requests.append((str(request.url), request.headers.get("Authorization"), json.loads(request.content)))
        return httpx.Response(
            200,
            json={
                "output": {
                    "results": [
                        {"index": 1, "relevance_score": 0.9345},
                        {"index": 0, "relevance_score": 0.412},
                    ]
                },
                "usage": {"total_tokens": 248},
                "request_id": "req-rerank-01",
            },
        )

    candidates = [
        {"id": 1, "doc_id": 11, "chunk_no": 1, "doc_title": "通用FAQ", "content": "接口调用常见问题说明"},
        {"id": 2, "doc_id": 12, "chunk_no": 2, "doc_title": "Webhook 限流规范", "content": "HTTP 429 退避重试策略"},
    ]
    out = reranker.rerank_candidates(
        "Webhook 429 怎么重试",
        candidates,
        top_k=2,
        tenant_code="TENANT_A",
        mode="qwen",
        _transport=httpx.MockTransport(handler),
    )
    assert out["rerank_mode"] == "qwen"
    assert [row["id"] for row in out["results"]] == [2, 1]
    assert out["results"][0]["rerank_score"] == 0.9345
    assert out["usage"]["total_tokens"] == 248
    assert out["usage"]["request_id"] == "req-rerank-01"
    url, auth, payload = seen_requests[0]
    assert url == "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
    assert auth == "Bearer sk-test-rerank"
    assert payload["model"] == "gte-rerank"
    assert payload["parameters"]["top_n"] == 2
    assert len(payload["input"]["documents"]) == 2


@pytest.mark.parametrize("fault", ["timeout", "http_503", "oversized", "bad_index"])
def test_qwen_reranker_falls_back_to_lexical_hybrid_on_remote_fault(monkeypatch, fault):
    settings = SimpleNamespace(
        rag_rerank_mode="qwen",
        rag_rerank_url="https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank",
        rag_rerank_model="gte-rerank",
        rag_rerank_timeout=5.0,
        rag_rerank_max_response_bytes=4096,
        rerank_key=lambda: "sk-test-rerank",
    )
    monkeypatch.setattr(reranker, "get_settings", lambda: settings)

    def handler(request: httpx.Request) -> httpx.Response:
        if fault == "timeout":
            raise httpx.ReadTimeout("timed out", request=request)
        if fault == "http_503":
            return httpx.Response(503, json={"message": "unavailable"})
        if fault == "oversized":
            return httpx.Response(200, content=b"x" * 8192)
        return httpx.Response(200, json={"output": {"results": [{"index": 99, "relevance_score": 0.9}]}})

    candidates = [
        {"id": 1, "doc_id": 10, "chunk_no": 1, "doc_title": "社区讨论", "content": "大家遇到 RMA 怎么寄回", "source_authority": "community"},
        {"id": 2, "doc_id": 20, "chunk_no": 2, "doc_title": "RMA 硬件返修流程", "content": "企业设备 RMA 返修单号有效期限为 7 个工作日。", "source_authority": "official"},
    ]
    out = reranker.rerank_candidates(
        "RMA 返修单号有效期限",
        candidates,
        top_k=2,
        mode="qwen",
        _transport=httpx.MockTransport(handler),
    )
    assert out["rerank_mode"] == "lexical_hybrid"
    assert out["usage"] is None
    assert out["fallback_reason"]
    assert out["results"][0]["id"] == 2


def test_lexical_hybrid_promotes_title_context_and_official_authority():
    candidates = [
        {
            "id": 101,
            "doc_id": 1,
            "chunk_no": 1,
            "doc_title": "社区经验贴",
            "content": "VIP 客户如果遇到故障赔付一般可以找客服问问有没有代金券补偿。",
            "source_authority": "community",
            "rrf_score": 0.031,
        },
        {
            "id": 102,
            "doc_id": 2,
            "chunk_no": 2,
            "doc_title": "VIP 专属 SLA 故障赔付标准",
            "content": "当月服务可用性未达承诺值时，按月度服务费 25% 核发故障赔付代金券。",
            "source_authority": "official",
            "rrf_score": 0.024,
        },
    ]
    out = reranker.rerank_candidates(
        "VIP 客户 SLA 故障赔付代金券怎么算",
        candidates,
        top_k=2,
        mode="lexical_hybrid",
    )
    assert out["rerank_mode"] == "lexical_hybrid"
    assert [row["id"] for row in out["results"]] == [102, 101]


def test_pipeline_and_retriever_support_hybrid_and_hybrid_rerank_modes(monkeypatch):
    settings = SimpleNamespace(
        rag_search_mode="auto",
        rag_hybrid_candidate_depth=10,
        rag_rrf_k=60,
        rag_rerank_mode="lexical_hybrid",
        rerank_key=lambda: "",
    )
    monkeypatch.setattr(pipeline, "get_settings", lambda: settings)
    monkeypatch.setattr(reranker, "get_settings", lambda: settings)
    monkeypatch.setattr(llama_engine, "available", lambda: False)
    monkeypatch.setattr(pipeline.embedding_module, "embed_texts", lambda texts, **_kw: ([[0.4] * 8], "local-hash"))
    monkeypatch.setattr(
        store,
        "search",
        lambda *_a, **_kw: [
            {"id": 1, "doc_id": 10, "chunk_no": 1, "doc_title": "SSO 配置", "content": "SAML 证书轮换平滑过渡期为 7 天。", "score": 0.65, "source_authority": "official"}
        ],
    )
    monkeypatch.setattr(
        store,
        "keyword_search",
        lambda *_a, **_kw: [
            {"id": 2, "doc_id": 20, "chunk_no": 1, "doc_title": "常见问题", "content": "登录密码重置指南。", "score": 0.25, "source_authority": "reference"},
            {"id": 1, "doc_id": 10, "chunk_no": 1, "doc_title": "SSO 配置", "content": "SAML 证书轮换平滑过渡期为 7 天。", "score": 0.50, "source_authority": "official"},
        ],
    )

    hybrid_res = retriever.retrieve_with_meta("SSO SAML 证书轮换", tenant_code="TENANT_A", top_k=2, search_mode="hybrid")
    assert hybrid_res["mode"] == "hybrid"
    assert hybrid_res["results"][0]["id"] == 1

    rerank_res = retriever.retrieve_with_meta("SSO SAML 证书轮换", tenant_code="TENANT_A", top_k=2, search_mode="hybrid_rerank")
    assert rerank_res["mode"] == "hybrid_rerank"
    assert rerank_res["rerank_mode"] == "lexical_hybrid"
    assert rerank_res["results"][0]["id"] == 1

    with pytest.raises(ValueError):
        pipeline.search("TENANT_A", "SSO", search_mode="invalid-mode")


def test_grounded_visitor_mode_abstains_when_only_community_chunks_exist(monkeypatch):
    monkeypatch.setattr(graph, "_graph", None)
    settings = SimpleNamespace(
        rag_vector_min_score=0.35,
        rag_keyword_min_score=0.12,
        rag_rerank_min_score=0.15,
        rag_require_grounded_reply=True,
        rag_require_extractive_support=False,
        rag_require_policy_review=True,
        llm_timeout=5,
    )
    monkeypatch.setattr(graph, "get_settings", lambda: settings)
    monkeypatch.setattr(rag, "get_settings", lambda: settings)
    monkeypatch.setattr(
        graph.retriever,
        "retrieve_with_meta",
        lambda **_kw: {
            "mode": "vector",
            "results": [
                {
                    "id": 91,
                    "doc_id": 901,
                    "chunk_no": 1,
                    "doc_title": "网友经验讨论",
                    "content": "听说企业版退款一般 1 小时就能到账。",
                    "score": 0.88,
                    "source_authority": "community",
                }
            ],
        },
    )
    monkeypatch.setattr(rag, "resolve_provider", _unexpected)

    result = asyncio.run(graph.ask("企业版退款多久到账？", "TENANT_A", published_only=True))
    assert result["decision"] == "abstain"
    assert result["abstention_reason"] == "insufficient_authority"
    assert result["citations"] == []
    assert "1 小时" not in result["answer"]


def test_official_policy_supersedes_conflicting_reference_chunk_without_false_conflict(monkeypatch):
    monkeypatch.setattr(graph, "_graph", None)
    settings = SimpleNamespace(
        rag_vector_min_score=0.35,
        rag_keyword_min_score=0.12,
        rag_rerank_min_score=0.15,
        rag_require_grounded_reply=True,
        rag_require_extractive_support=False,
        rag_require_policy_review=True,
        llm_timeout=5,
    )
    monkeypatch.setattr(graph, "get_settings", lambda: settings)
    monkeypatch.setattr(rag, "get_settings", lambda: settings)
    monkeypatch.setattr(graph.store, "published_evidence_is_current", lambda *_: True)
    monkeypatch.setattr(rag, "resolve_provider", lambda _: ({"model": "fixture"}, "fixture"))

    official_text = "日租录音棚超时计费按每30分钟12元结算。适用于所有日租录音棚预约。"
    reference_text = "日租录音棚超时计费按每30分钟18元结算。适用于所有日租录音棚预约。"

    assert not grounded_reply.conflicting_policy_numbers(
        "日租录音棚超时计费多少钱？",
        [
            {"doc_id": "31", "content": official_text, "source_authority": "official"},
            {"doc_id": "32", "content": reference_text, "source_authority": "reference"},
        ],
    )
    assert grounded_reply.conflicting_policy_numbers(
        "日租录音棚超时计费多少钱？",
        [
            {"doc_id": "31", "content": official_text, "source_authority": "official"},
            {"doc_id": "32", "content": reference_text, "source_authority": "official"},
        ],
    )

    monkeypatch.setattr(
        graph.retriever,
        "retrieve_with_meta",
        lambda **_kw: {
            "mode": "vector",
            "results": [
                {
                    "id": 51,
                    "doc_id": 32,
                    "chunk_no": 1,
                    "doc_title": "旧版培训讲义",
                    "content": reference_text,
                    "score": 0.85,
                    "source_authority": "reference",
                },
                {
                    "id": 52,
                    "doc_id": 31,
                    "chunk_no": 1,
                    "doc_title": "正式计费公示",
                    "content": official_text,
                    "score": 0.81,
                    "source_authority": "official",
                },
            ],
        },
    )

    seen_prompts = []

    async def fake_chat(_provider, payload, _timeout, **_kwargs):
        seen_prompts.append(json.loads(payload["messages"][1]["content"]))
        body = {
            "decision": "answer",
            "claims": [
                {
                    "text": "日租录音棚超时按每30分钟12元结算。",
                    "sources": [{"index": 1, "quote": "日租录音棚超时计费按每30分钟12元结算。"}],
                }
            ],
        }
        return {"choices": [{"message": {"content": json.dumps(body, ensure_ascii=False)}}]}

    monkeypatch.setattr(rag, "call_chat", fake_chat)
    result = asyncio.run(graph.ask("日租录音棚超时计费多少钱？", "TENANT_A", published_only=True))
    assert result["decision"] == "answer"
    assert result["handoff_recommended"] is False
    assert "12元" in result["answer"]
    assert [c["doc_id"] for c in result["citations"]] == ["31"]
    assert len(seen_prompts) == 1
    assert len(seen_prompts[0]["sources"]) == 1
    assert "18元" not in seen_prompts[0]["sources"][0]["content"]


def test_parse_grounded_reply_rejects_community_citation():
    quote = "社区传言可随时全额退费。"
    payload = json.dumps(
        {"decision": "answer", "claims": [{"text": "可随时退费。", "sources": [{"index": 1, "quote": quote}]}]},
        ensure_ascii=False,
    )
    with pytest.raises(ValueError, match="unverified grounded reply source"):
        grounded_reply.parse_grounded_reply(
            payload,
            [{"index": 1, "content": quote, "source_authority": "community"}],
        )
