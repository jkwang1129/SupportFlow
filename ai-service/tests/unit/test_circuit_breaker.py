"""Unit tests for provider-isolated CircuitBreaker, chat_transport opt-in, and ModelGateway."""

from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
import json
from typing import Any

import httpx
import pytest

from ai.core.chat_transport import ModelCallFailure, _send_chat_json
from ai.core.circuit_breaker import (
    CircuitBreaker,
    CircuitBreakerOpenError,
    CircuitBreakerRegistry,
    CircuitState,
)
from ai.core.llm_gateway import ModelGateway, ProviderRoute
from ai.core.telemetry import InMemorySpanExporter, get_span_recorder


class FakeClock:
    def __init__(self, initial: float = 1000.0) -> None:
        self.now = initial

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


class _AsyncStreamTransport(httpx.AsyncBaseTransport):
    def __init__(self, handler) -> None:
        self._handler = handler

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        status, body_bytes = self._handler(request)
        return httpx.Response(
            status_code=status,
            headers=[("Content-Type", "application/json"), ("Content-Encoding", "identity")],
            stream=httpx.ByteStream(body_bytes),
        )


def _make_json_transport(handler) -> httpx.AsyncBaseTransport:
    return _AsyncStreamTransport(handler)


def test_circuit_breaker_transitions_closed_open_half_open_closed() -> None:
    clock = FakeClock()
    breaker = CircuitBreaker(
        "qwen",
        failure_threshold=3,
        recovery_timeout_seconds=30.0,
        half_open_max_calls=1,
        clock=clock,
    )

    assert breaker.state is CircuitState.CLOSED

    # 2 transient failures -> still CLOSED, then 1 success resets consecutive failures
    breaker.record_failure(transient=True, reason="MODEL_UNAVAILABLE")
    breaker.record_failure(transient=True, reason="MODEL_RATE_LIMITED")
    assert breaker.snapshot()["failure_count"] == 2
    breaker.record_success()
    assert breaker.snapshot()["failure_count"] == 0
    assert breaker.state is CircuitState.CLOSED

    # Non-transient failure (e.g. HTTP 400) does NOT trip breaker
    for _ in range(5):
        breaker.record_failure(transient=False, reason="MODEL_REQUEST_REJECTED")
    assert breaker.state is CircuitState.CLOSED
    assert breaker.snapshot()["failure_count"] == 0

    # 3 consecutive transient failures -> trips OPEN
    for _ in range(3):
        breaker.record_failure(transient=True, reason="MODEL_UNAVAILABLE")

    snap = breaker.snapshot()
    assert snap["state"] == "OPEN"
    assert snap["failure_count"] == 3
    assert snap["opened_at"] == 1000.0
    assert snap["total_trips"] == 1

    # Rejects calls while OPEN before cooldown
    clock.advance(12.0)
    with pytest.raises(CircuitBreakerOpenError) as exc_info:
        breaker.before_call()
    assert exc_info.value.provider == "qwen"
    assert exc_info.value.retry_after_seconds == pytest.approx(18.0)
    assert breaker.snapshot()["rejected_calls"] == 1

    # Advance past 30s cooldown -> HALF_OPEN
    clock.advance(18.5)
    assert breaker.state is CircuitState.HALF_OPEN

    # First probe acquires the single HALF_OPEN slot; concurrent second probe is rejected
    assert breaker.before_call() is CircuitState.HALF_OPEN
    with pytest.raises(CircuitBreakerOpenError):
        breaker.before_call()
    assert breaker.snapshot()["rejected_calls"] == 2

    # Probe succeeds -> transitions back to CLOSED
    breaker.record_success()
    closed_snap = breaker.snapshot()
    assert closed_snap["state"] == "CLOSED"
    assert closed_snap["failure_count"] == 0
    assert closed_snap["opened_at"] is None
    assert closed_snap["total_trips"] == 1


def test_half_open_probe_failure_reopens_immediately() -> None:
    clock = FakeClock()
    breaker = CircuitBreaker(
        "deepseek",
        failure_threshold=2,
        recovery_timeout_seconds=20.0,
        half_open_max_calls=1,
        clock=clock,
    )
    breaker.record_failure(transient=True, reason="MODEL_TIMEOUT")
    breaker.record_failure(transient=True, reason="MODEL_TIMEOUT")
    assert breaker.state is CircuitState.OPEN
    assert breaker.snapshot()["total_trips"] == 1

    clock.advance(20.0)
    assert breaker.before_call() is CircuitState.HALF_OPEN

    # Single probe failure must immediately re-open circuit
    breaker.record_failure(transient=True, reason="MODEL_UNAVAILABLE")
    snap = breaker.snapshot()
    assert snap["state"] == "OPEN"
    assert snap["opened_at"] == 1020.0
    assert snap["total_trips"] == 2


def test_registry_isolates_providers_and_thread_safety() -> None:
    clock = FakeClock()
    registry = CircuitBreakerRegistry(
        failure_threshold=4,
        recovery_timeout_seconds=30.0,
        clock=clock,
    )

    qwen_cb = registry.get("qwen")
    deepseek_cb = registry.get("deepseek")

    for _ in range(4):
        qwen_cb.record_failure(transient=True, reason="MODEL_RATE_LIMITED")

    assert qwen_cb.state is CircuitState.OPEN
    assert deepseek_cb.state is CircuitState.CLOSED
    assert deepseek_cb.call(lambda x: x + 1, 41) == 42

    # Thread-safe concurrent calls against OPEN breaker
    def attempt_call() -> str:
        try:
            qwen_cb.before_call()
            return "allowed"
        except CircuitBreakerOpenError:
            return "rejected"

    with ThreadPoolExecutor(max_workers=12) as pool:
        outcomes = list(pool.map(lambda _: attempt_call(), range(40)))
    assert outcomes == ["rejected"] * 40
    assert qwen_cb.snapshot()["rejected_calls"] == 40


def test_chat_transport_circuit_breaker_integration() -> None:
    clock = FakeClock()
    breaker = CircuitBreaker("qwen", failure_threshold=2, recovery_timeout_seconds=15.0, clock=clock)
    calls = 0

    def handler(_request: httpx.Request) -> tuple[int, bytes]:
        nonlocal calls
        calls += 1
        if calls <= 2:
            return 503, b"{}"
        body = json.dumps({"id": "resp-1", "model": "qwen-plus", "choices": [{"message": {"content": "ok"}}]})
        return 200, body.encode("utf-8")

    chosen = {
        "provider": "qwen",
        "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "api_key": "sk-testkey123456",
        "model": "qwen-plus",
    }
    payload = {"model": "qwen-plus", "messages": [{"role": "user", "content": "ping"}]}
    transport = _make_json_transport(handler)

    for _ in range(2):
        with pytest.raises(ModelCallFailure) as exc:
            asyncio.run(_send_chat_json(chosen, payload, 5.0, transport=transport, circuit_breaker=breaker))
        assert exc.value.code == "MODEL_UNAVAILABLE"

    assert breaker.state is CircuitState.OPEN
    # 3rd call blocked before touching network
    with pytest.raises(CircuitBreakerOpenError):
        asyncio.run(_send_chat_json(chosen, payload, 5.0, transport=transport, circuit_breaker=breaker))
    assert calls == 2

    # Advance clock past cooldown -> probe succeeds -> breaker closes
    clock.advance(16.0)
    data = asyncio.run(_send_chat_json(chosen, payload, 5.0, transport=transport, circuit_breaker=breaker))
    assert data["choices"][0]["message"]["content"] == "ok"
    assert breaker.state is CircuitState.CLOSED
    assert calls == 3


def test_model_gateway_multi_provider_fallback_degrade_and_telemetry() -> None:
    clock = FakeClock()
    registry = CircuitBreakerRegistry(failure_threshold=2, recovery_timeout_seconds=30.0, clock=clock)

    def failing_qwen(_req: httpx.Request) -> tuple[int, bytes]:
        return 429, b"{}"

    deepseek_fail = False

    def dynamic_deepseek(_req: httpx.Request) -> tuple[int, bytes]:
        if deepseek_fail:
            return 502, b"{}"
        resp_payload: dict[str, Any] = {
            "id": "ds-001",
            "model": "deepseek-chat",
            "choices": [{"message": {"role": "assistant", "content": "由 DeepSeek 备用路由完成解答"}}],
            "usage": {"prompt_tokens": 28, "completion_tokens": 12, "total_tokens": 40},
        }
        return 200, json.dumps(resp_payload, ensure_ascii=False).encode("utf-8")

    routes = {
        "qwen": ProviderRoute(
            provider="qwen",
            base_url="https://qwen.example.com/v1",
            api_key="sk-qwen-secret-key",
            model="qwen-plus",
            timeout_seconds=5.0,
        ),
        "deepseek": ProviderRoute(
            provider="deepseek",
            base_url="https://deepseek.example.com/v1",
            api_key="sk-deepseek-secret-key",
            model="deepseek-chat",
            timeout_seconds=5.0,
        ),
    }

    exporter = InMemorySpanExporter()
    recorder = get_span_recorder()
    recorder.add_exporter(exporter)
    try:
        gw = ModelGateway(
            default_provider="qwen",
            routes=routes,
            fallback_order=["deepseek"],
            circuit_breakers=registry,
            degrade_hook=lambda _msgs, reason: f"[规则兜底] 当前模型繁忙({reason})，已为您转接人工坐席。",
            provider_transports={
                "qwen": _make_json_transport(failing_qwen),
                "deepseek": _make_json_transport(dynamic_deepseek),
            },
        )

        # Call 1 & 2: qwen fails with 429 -> automatically falls back to deepseek; qwen trips OPEN on 2nd failure
        r1 = gw.invoke([{"role": "user", "content": "查询退货进度"}])
        r2 = gw.invoke([{"role": "user", "content": "查询发票进度"}])
        assert r1.provider == "deepseek"
        assert r1.fallback_used is True
        assert r1.total_tokens == 40
        assert r2.provider == "deepseek"
        assert registry.get("qwen").state is CircuitState.OPEN
        assert registry.get("deepseek").state is CircuitState.CLOSED

        # Now make deepseek also fail -> triggers automatic rule degradation hook
        deepseek_fail = True
        r3 = gw.invoke([{"role": "user", "content": "我要加急处理工单"}])
        assert r3.degraded is True
        assert r3.provider == "rule_degraded"
        assert "[规则兜底]" in r3.content

        metrics = gw.metrics_snapshot()
        assert metrics["providers"]["deepseek"]["successes"] == 2
        assert metrics["providers"]["deepseek"]["total_tokens"] == 80
        assert metrics["providers"]["rule_degraded"]["degraded"] == 1
        assert metrics["circuit_breakers"]["qwen"]["state"] == "OPEN"

        model_spans = [s for s in exporter.get_spans() if s.name == "model.invoke"]
        assert len(model_spans) >= 4
        assert any(s.attributes.get("llm.total_tokens") == 40 for s in model_spans)
    finally:
        recorder.remove_exporter(exporter)
