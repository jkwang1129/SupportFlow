"""Unit tests for OpenTelemetry + W3C Trace Context + Langfuse telemetry & PII redaction."""

from __future__ import annotations

import asyncio
import json

from fastapi import FastAPI
from fastapi.testclient import TestClient
import httpx
import pytest

from ai.core.telemetry import (
    KEY_EXECUTION_NODES,
    InMemorySpanExporter,
    LangfuseIngestionExporter,
    SpanLink,
    SpanRecorder,
    extract_trace_context,
    format_traceparent,
    inject_trace_headers,
    parse_traceparent,
    redact_attributes,
    start_span,
    verify_key_node_coverage,
)
from ai.core.trace import TRACE_ID_HEADER, TRACEPARENT_HEADER, bind_trace_id, get_trace_id
from ai.main import _install_trace_middleware


def test_w3c_traceparent_parse_format_and_header_injection() -> None:
    valid = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    parsed = parse_traceparent(valid)
    assert parsed is not None
    assert parsed.version == "00"
    assert parsed.trace_id == "4bf92f3577b34da6a3ce929d0e0e4736"
    assert parsed.span_id == "00f067aa0ba902b7"
    assert parsed.sampled is True
    assert parsed.to_header() == valid

    # Reject malformed / invalid W3C traceparents
    assert parse_traceparent("") is None
    assert parse_traceparent("ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01") is None
    assert parse_traceparent(f"00-{'0' * 32}-00f067aa0ba902b7-01") is None
    assert parse_traceparent(f"00-4bf92f3577b34da6a3ce929d0e0e4736-{'0' * 16}-01") is None
    assert parse_traceparent(valid.upper()) is None

    recorder = SpanRecorder()
    with bind_trace_id("req-java-001"):
        with start_span("http.request", kind="SERVER", parent_traceparent=valid, recorder=recorder) as root:
            outbound = inject_trace_headers({"Accept": "application/json"})
            assert outbound[TRACE_ID_HEADER] == "req-java-001"
            extracted = extract_trace_context(outbound)
            assert extracted is not None
            assert extracted.trace_id == "4bf92f3577b34da6a3ce929d0e0e4736"
            assert extracted.span_id == root.span_id


def test_pii_and_secret_redaction_preserves_numeric_token_counts() -> None:
    raw_attrs = {
        "api_key": "sk-live-secret99999999",
        "Authorization": "Bearer eyJhbGciOiJIUzI1NiJ9.secret",
        "client_secret": "top-secret-val",
        "access_token": "tok_abc123",
        "db_password": "postgres_password",
        "customer.note": "请回电客户 13812345678 或 +86 13987654321，附带 Bearer secretToken123456",
        "nested": {
            "token": "raw-token-value",
            "contact": "手机15800009999",
        },
        "llm.prompt_tokens": 512,
        "llm.completion_tokens": 128,
        "llm.total_tokens": 640,
    }
    cleaned = redact_attributes(raw_attrs)

    assert cleaned["api_key"] == "[REDACTED]"
    assert cleaned["Authorization"] == "[REDACTED]"
    assert cleaned["client_secret"] == "[REDACTED]"
    assert cleaned["access_token"] == "[REDACTED]"
    assert cleaned["db_password"] == "[REDACTED]"
    assert cleaned["nested"]["token"] == "[REDACTED]"
    assert cleaned["nested"]["contact"] == "手机158****9999"
    assert "13812345678" not in cleaned["customer.note"]
    assert "138****5678" in cleaned["customer.note"]
    assert "139****4321" in cleaned["customer.note"]
    assert "Bearer [REDACTED]" in cleaned["customer.note"]
    # Numeric LLM token metrics remain intact
    assert cleaned["llm.prompt_tokens"] == 512
    assert cleaned["llm.completion_tokens"] == 128
    assert cleaned["llm.total_tokens"] == 640


def test_span_links_async_concurrency_and_key_node_coverage() -> None:
    recorder = SpanRecorder()

    # Simulate full 10-node trace + async Outbox -> Worker and HITL pause -> resume SpanLinks
    with start_span("http.request", kind="SERVER", recorder=recorder) as http_span:
        with start_span("router.decide", recorder=recorder):
            pass
        with start_span("context.build", recorder=recorder):
            pass
        with start_span("rag.retrieve", recorder=recorder):
            with start_span("rag.rerank", recorder=recorder):
                pass
        with start_span("agent.run", recorder=recorder):
            with start_span("agent.step", recorder=recorder) as step_span:
                with start_span(
                    "model.invoke",
                    kind="CLIENT",
                    attributes={"llm.model": "qwen-plus", "llm.total_tokens": 310},
                    recorder=recorder,
                ):
                    pass
                with start_span("tool.execute", attributes={"tool.name": "query_order"}, recorder=recorder):
                    pass
                outbox_tp = step_span.traceparent

    # Async Worker picks up outbox event and HITL resume links back to originating step_span
    hitl_link = SpanLink.from_traceparent(
        outbox_tp,
        attributes={"link.type": "hitl_pause_resume", "approval_token": "secret-tok"},
    )
    with start_span("hitl.approval", kind="CONSUMER", links=[hitl_link], recorder=recorder) as hitl_span:
        hitl_span.set_attribute("hitl.decision", "APPROVED")

    spans = recorder.get_spans()
    audit = verify_key_node_coverage(spans, KEY_EXECUTION_NODES)
    assert audit["complete"] is True
    assert audit["coverage_rate"] == 1.0
    assert audit["missing_nodes"] == []
    assert audit["broken_parent_refs"] == []

    # Verify SpanLink causal link & redaction on link attributes
    assert len(hitl_span.links) == 1
    assert hitl_span.links[0].trace_id == http_span.trace_id
    assert hitl_span.links[0].attributes["approval_token"] == "[REDACTED]"

    # Verify missing node detection
    partial_audit = verify_key_node_coverage(spans[:4], KEY_EXECUTION_NODES)
    assert partial_audit["complete"] is False
    assert "tool.execute" in partial_audit["missing_nodes"]

    # Verify contextvar isolation across concurrent asyncio tasks
    async def worker_task(idx: int) -> tuple[str, str]:
        with start_span(f"worker.{idx}", recorder=recorder) as parent:
            await asyncio.sleep(0.005)
            with start_span(f"worker.{idx}.child", recorder=recorder) as child:
                return parent.span_id, child.parent_span_id or ""

    async def run_concurrent():
        return await asyncio.gather(*(worker_task(i) for i in range(6)))

    pairs = asyncio.run(run_concurrent())
    for parent_id, observed_parent_id in pairs:
        assert parent_id == observed_parent_id


def test_langfuse_exporter_offline_zero_network_and_configured_batch_export() -> None:
    network_calls = 0

    def forbid_network(_req: httpx.Request) -> httpx.Response:
        nonlocal network_calls
        network_calls += 1
        raise AssertionError("Network must never be called when Langfuse keys are unconfigured")

    offline_store = InMemorySpanExporter()
    unconfigured = LangfuseIngestionExporter(
        public_key="",
        secret_key="",
        transport=httpx.MockTransport(forbid_network),
        offline_fallback=offline_store,
    )
    assert unconfigured.is_configured is False

    recorder = SpanRecorder()
    recorder.add_exporter(unconfigured)
    with start_span("context.build", attributes={"api_key": "sk-123456789012345"}, recorder=recorder):
        pass
    assert network_calls == 0
    assert len(offline_store.get_spans()) == 1

    # Configured Langfuse exporter batches to /api/public/ingestion
    captured_batches: list[dict] = []

    def mock_langfuse_ingestion(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/public/ingestion"
        assert request.headers.get("authorization", "").startswith("Basic ")
        body = json.loads(request.content.decode("utf-8"))
        captured_batches.append(body)
        return httpx.Response(207, json={"successes": len(body["batch"]), "errors": []})

    configured = LangfuseIngestionExporter(
        public_key="pk-lf-test",
        secret_key="sk-lf-test",
        base_url="https://langfuse.internal.example",
        batch_size=2,
        transport=httpx.MockTransport(mock_langfuse_ingestion),
    )
    assert configured.is_configured is True

    live_recorder = SpanRecorder()
    live_recorder.add_exporter(configured)
    with start_span(
        "model.invoke",
        kind="CLIENT",
        attributes={
            "llm.model": "qwen-plus",
            "llm.prompt_tokens": 200,
            "llm.completion_tokens": 50,
            "llm.total_tokens": 250,
            "api_key": "sk-should-be-redacted-9999",
        },
        recorder=live_recorder,
    ):
        pass
    assert configured.flush() is True
    assert len(captured_batches) == 1
    events = captured_batches[0]["batch"]
    event_types = {e["type"] for e in events}
    assert "trace-create" in event_types
    assert "generation-create" in event_types
    serialized_batch = json.dumps(captured_batches[0])
    assert "sk-should-be-redacted-9999" not in serialized_batch


def test_fastapi_trace_middleware_preserves_request_id_and_propagates_traceparent() -> None:
    mini_app = FastAPI()
    _install_trace_middleware(mini_app)

    @mini_app.get("/ping")
    async def _ping():
        return {"trace_id": get_trace_id()}

    client = TestClient(mini_app)
    incoming_tp = format_traceparent("abcdef0123456789abcdef0123456789", "1122334455667788")
    resp = client.get(
        "/ping",
        headers={
            TRACE_ID_HEADER: "req-custom-77",
            TRACEPARENT_HEADER: incoming_tp,
        },
    )
    assert resp.status_code == 200
    assert resp.json()["trace_id"] == "req-custom-77"
    assert resp.headers[TRACE_ID_HEADER] == "req-custom-77"

    returned_tp = parse_traceparent(resp.headers.get(TRACEPARENT_HEADER))
    assert returned_tp is not None
    assert returned_tp.trace_id == "abcdef0123456789abcdef0123456789"
    assert returned_tp.span_id != "1122334455667788"

    # Also verify automatic X-Request-Id + traceparent generation when headers omitted
    resp_auto = client.get("/ping")
    assert resp_auto.status_code == 200
    assert len(resp_auto.headers[TRACE_ID_HEADER]) == 16
    assert parse_traceparent(resp_auto.headers[TRACEPARENT_HEADER]) is not None
