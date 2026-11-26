"""Production LLM Gateway: multi-provider routing, circuit breaking, fallback, and telemetry."""

from __future__ import annotations

import asyncio
from collections.abc import Callable, Mapping, Sequence
from concurrent.futures import ThreadPoolExecutor
import contextvars
from dataclasses import dataclass
import logging
import math
import re
import threading
import time
from typing import Any, Protocol

import httpx

from ..config import get_settings
from .chat_transport import ModelCallFailure, _send_chat_json
from .circuit_breaker import (
    CircuitBreakerOpenError,
    CircuitBreakerRegistry,
)
from .telemetry import start_span
from .trace import get_trace_id

logger = logging.getLogger(__name__)

_CJK_RE = re.compile(r"[\u3000-\u303f\u3400-\u4dbf\u4e00-\u9fff\uff00-\uffef]")
_ASCII_TOKEN_RE = re.compile(r"[A-Za-z0-9_]+|[^\s\u3000-\u303f\u3400-\u4dbf\u4e00-\u9fff\uff00-\uffef]")


class ChatModel(Protocol):
    """Synchronous chat generation protocol."""

    def generate(self, prompt: str) -> str: ...


class MockModel:
    """Deterministic offline mock model for local development and tests."""

    def generate(self, prompt: str) -> str:
        return f"[mock] 已收到你的消息：{prompt[:60]}（接入大模型后由 LLM 网关真实生成）"


@dataclass(frozen=True)
class ProviderRoute:
    """Provider connection configuration without leaking credentials in repr."""

    provider: str
    base_url: str
    api_key: str = field_api_key if False else ""
    model: str = ""
    timeout_seconds: float = 15.0

    def __repr__(self) -> str:
        return (
            f"ProviderRoute(provider={self.provider!r}, base_url={self.base_url!r}, "
            f"model={self.model!r}, timeout_seconds={self.timeout_seconds!r})"
        )

    def is_configured(self) -> bool:
        if self.provider == "mock":
            return True
        return bool(self.base_url and self.api_key and self.model)

    def to_transport_dict(self, *, model_override: str | None = None) -> dict[str, str]:
        return {
            "provider": self.provider,
            "base_url": self.base_url,
            "api_key": self.api_key,
            "model": model_override or self.model,
        }


@dataclass(frozen=True)
class GatewayResponse:
    """Normalized LLM gateway result with token usage, latency, and degradation metadata."""

    content: str
    provider: str
    model: str
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int
    latency_ms: float
    fallback_used: bool = False
    degraded: bool = False
    degrade_reason: str | None = None
    trace_id: str = ""


def _estimate_text_tokens(text: str) -> int:
    if not text:
        return 0
    cjk_count = len(_CJK_RE.findall(text))
    ascii_count = len(_ASCII_TOKEN_RE.findall(text))
    return max(1, int(math.ceil(cjk_count * 0.7 + ascii_count)))


def _extract_assistant_text(data: Mapping[str, Any]) -> str:
    choices = data.get("choices")
    if not isinstance(choices, list) or not choices:
        return ""
    first = choices[0]
    if not isinstance(first, Mapping):
        return ""
    message = first.get("message")
    if isinstance(message, Mapping) and isinstance(message.get("content"), str):
        return message["content"]
    return ""


def _extract_usage(
    data: Mapping[str, Any],
    messages: Sequence[Mapping[str, Any]],
    reply: str,
) -> tuple[int, int, int]:
    usage = data.get("usage")
    if isinstance(usage, Mapping):
        pt = usage.get("prompt_tokens")
        ct = usage.get("completion_tokens")
        tt = usage.get("total_tokens")
        if isinstance(pt, int) and isinstance(ct, int):
            total = tt if isinstance(tt, int) else (pt + ct)
            return pt, ct, total
    est_prompt = sum(
        _estimate_text_tokens(str(m.get("content") or "")) + 4 for m in messages
    )
    est_completion = _estimate_text_tokens(reply)
    return est_prompt, est_completion, est_prompt + est_completion


class ModelGateway:
    """Multi-provider LLM gateway (`qwen` / `deepseek` / `mock`) with circuit breakers and fallback."""

    SUPPORTED_PROVIDERS: tuple[str, ...] = ("qwen", "deepseek", "mock")

    def __init__(
        self,
        *,
        default_provider: str | None = None,
        default_model: str | None = None,
        routes: Mapping[str, ProviderRoute] | None = None,
        fallback_order: Sequence[str] | None = None,
        circuit_breakers: CircuitBreakerRegistry | None = None,
        degrade_hook: Callable[[Sequence[Mapping[str, Any]], str], str] | None = None,
        transport: httpx.AsyncBaseTransport | None = None,
        provider_transports: Mapping[str, httpx.AsyncBaseTransport] | None = None,
    ) -> None:
        settings = get_settings()
        self.provider = (default_provider or settings.llm_default_provider or "mock").strip().lower()
        self.model = (default_model if default_model is not None else settings.llm_default_model) or ""
        self._mock_model: ChatModel = MockModel()
        self._explicit_routes = dict(routes) if routes is not None else None
        self._fallback_order = [p.strip().lower() for p in fallback_order] if fallback_order is not None else None
        self.circuit_breakers = circuit_breakers or CircuitBreakerRegistry()
        self._degrade_hook = degrade_hook
        self._transport = transport
        self._provider_transports = dict(provider_transports) if provider_transports else {}
        self._metrics_lock = threading.RLock()
        self._stats: dict[str, dict[str, float | int]] = {}

        logger.info(
            "LLM 网关初始化 provider=%s model=%s mode=%s",
            self.provider,
            self.model or "-",
            "mock" if self.provider == "mock" else "remote",
        )

    def _resolve_routes(self) -> dict[str, ProviderRoute]:
        if self._explicit_routes is not None:
            return dict(self._explicit_routes)
        settings = get_settings()
        timeout = float(getattr(settings, "llm_timeout", 15.0) or 15.0)
        return {
            "qwen": ProviderRoute(
                provider="qwen",
                base_url=getattr(settings, "qwen_base_url", ""),
                api_key=getattr(settings, "qwen_api_key", ""),
                model=self.model if self.provider == "qwen" and self.model else getattr(settings, "qwen_model", ""),
                timeout_seconds=timeout,
            ),
            "deepseek": ProviderRoute(
                provider="deepseek",
                base_url=getattr(settings, "deepseek_base_url", ""),
                api_key=getattr(settings, "deepseek_api_key", ""),
                model=(
                    self.model
                    if self.provider == "deepseek" and self.model
                    else getattr(settings, "deepseek_model", "")
                ),
                timeout_seconds=timeout,
            ),
            "mock": ProviderRoute(
                provider="mock",
                base_url="mock://local",
                api_key="mock",
                model="mock",
                timeout_seconds=timeout,
            ),
        }

    def _candidate_providers(
        self,
        primary: str,
        routes: Mapping[str, ProviderRoute],
        fallback_override: Sequence[str] | None,
    ) -> list[str]:
        candidates = [primary]
        configured_fallbacks = (
            [p.strip().lower() for p in fallback_override]
            if fallback_override is not None
            else self._fallback_order
        )
        if configured_fallbacks is not None:
            for item in configured_fallbacks:
                if item and item not in candidates:
                    candidates.append(item)
        elif primary in ("qwen", "deepseek"):
            peer = "deepseek" if primary == "qwen" else "qwen"
            peer_route = routes.get(peer)
            if peer_route is not None and peer_route.is_configured():
                candidates.append(peer)
        return candidates

    def _record_metric(
        self,
        provider: str,
        *,
        succeeded: bool,
        prompt_tokens: int = 0,
        completion_tokens: int = 0,
        latency_ms: float = 0.0,
        fallback_used: bool = False,
        degraded: bool = False,
    ) -> None:
        with self._metrics_lock:
            bucket = self._stats.setdefault(
                provider,
                {
                    "calls": 0,
                    "successes": 0,
                    "failures": 0,
                    "fallbacks": 0,
                    "degraded": 0,
                    "prompt_tokens": 0,
                    "completion_tokens": 0,
                    "total_tokens": 0,
                    "total_latency_ms": 0.0,
                },
            )
            bucket["calls"] = int(bucket["calls"]) + 1
            if succeeded:
                bucket["successes"] = int(bucket["successes"]) + 1
            else:
                bucket["failures"] = int(bucket["failures"]) + 1
            if fallback_used:
                bucket["fallbacks"] = int(bucket["fallbacks"]) + 1
            if degraded:
                bucket["degraded"] = int(bucket["degraded"]) + 1
            bucket["prompt_tokens"] = int(bucket["prompt_tokens"]) + int(prompt_tokens)
            bucket["completion_tokens"] = int(bucket["completion_tokens"]) + int(completion_tokens)
            bucket["total_tokens"] = int(bucket["total_tokens"]) + int(prompt_tokens + completion_tokens)
            bucket["total_latency_ms"] = round(float(bucket["total_latency_ms"]) + float(latency_ms), 3)

    def metrics_snapshot(self) -> dict[str, Any]:
        """Return per-provider gateway invocation metrics and circuit breaker snapshots."""
        with self._metrics_lock:
            providers = {k: dict(v) for k, v in self._stats.items()}
        return {
            "default_provider": self.provider,
            "providers": providers,
            "circuit_breakers": self.circuit_breakers.snapshots(),
        }

    async def invoke_async(
        self,
        messages: Sequence[Mapping[str, Any]],
        *,
        provider: str | None = None,
        model: str | None = None,
        temperature: float = 0.2,
        max_tokens: int | None = None,
        fallback_providers: Sequence[str] | None = None,
        degrade_hook: Callable[[Sequence[Mapping[str, Any]], str], str] | None = None,
    ) -> GatewayResponse:
        """Invoke chat model asynchronously with circuit breaker protection and fallback."""
        normalized_messages = [
            {"role": str(m.get("role") or "user"), "content": str(m.get("content") or "")}
            for m in messages
        ]
        if not normalized_messages:
            raise ModelCallFailure("MODEL_REQUEST_INVALID", attempted=False)

        routes = self._resolve_routes()
        primary = (provider or self.provider or "mock").strip().lower()
        candidates = self._candidate_providers(primary, routes, fallback_providers)
        active_degrade_hook = degrade_hook or self._degrade_hook
        trace_id = get_trace_id()
        last_error: Exception | None = None
        last_reason = "PROVIDER_UNAVAILABLE"

        for idx, candidate in enumerate(candidates):
            is_fallback = idx > 0
            route = routes.get(candidate)
            if route is None or candidate not in self.SUPPORTED_PROVIDERS:
                last_reason = "PROVIDER_UNSUPPORTED"
                last_error = ModelCallFailure("MODEL_CONFIGURATION_INVALID", attempted=False)
                continue

            target_model = (model if idx == 0 and model else route.model) or candidate
            breaker = self.circuit_breakers.get(candidate)
            started = time.perf_counter()

            with start_span(
                "model.invoke",
                kind="CLIENT",
                attributes={
                    "llm.provider": candidate,
                    "llm.model": target_model,
                    "llm.fallback_attempt": is_fallback,
                    "circuit_breaker.state_before": breaker.state.value,
                },
            ) as span:
                if candidate == "mock":
                    last_user = next(
                        (m["content"] for m in reversed(normalized_messages) if m["role"] == "user"),
                        normalized_messages[-1]["content"],
                    )
                    reply = self._mock_model.generate(last_user)
                    latency_ms = round((time.perf_counter() - started) * 1000, 3)
                    pt, ct, tt = _extract_usage({}, normalized_messages, reply)
                    span.set_attributes(
                        {
                            "llm.prompt_tokens": pt,
                            "llm.completion_tokens": ct,
                            "llm.total_tokens": tt,
                            "llm.latency_ms": latency_ms,
                            "circuit_breaker.state_after": breaker.state.value,
                        }
                    )
                    self._record_metric(
                        candidate,
                        succeeded=True,
                        prompt_tokens=pt,
                        completion_tokens=ct,
                        latency_ms=latency_ms,
                        fallback_used=is_fallback,
                    )
                    return GatewayResponse(
                        content=reply,
                        provider=candidate,
                        model="mock",
                        prompt_tokens=pt,
                        completion_tokens=ct,
                        total_tokens=tt,
                        latency_ms=latency_ms,
                        fallback_used=is_fallback,
                        trace_id=trace_id,
                    )

                if not route.is_configured():
                    err = ModelCallFailure("MODEL_CONFIGURATION_INVALID", attempted=False)
                    span.record_exception(err)
                    last_error = err
                    last_reason = err.code
                    self._record_metric(candidate, succeeded=False)
                    continue

                payload: dict[str, Any] = {
                    "model": target_model,
                    "messages": normalized_messages,
                    "temperature": float(temperature),
                }
                if max_tokens is not None:
                    payload["max_tokens"] = int(max_tokens)

                chosen_transport = self._provider_transports.get(candidate, self._transport)
                try:
                    data = await _send_chat_json(
                        route.to_transport_dict(model_override=target_model),
                        payload,
                        route.timeout_seconds,
                        transport=chosen_transport,
                        circuit_breaker=breaker,
                    )
                    reply = _extract_assistant_text(data)
                    if not reply:
                        raise ModelCallFailure("MODEL_EMPTY_RESPONSE")
                    latency_ms = round((time.perf_counter() - started) * 1000, 3)
                    pt, ct, tt = _extract_usage(data, normalized_messages, reply)
                    returned_model = str(data.get("model") or target_model)
                    span.set_attributes(
                        {
                            "llm.model": returned_model,
                            "llm.prompt_tokens": pt,
                            "llm.completion_tokens": ct,
                            "llm.total_tokens": tt,
                            "llm.latency_ms": latency_ms,
                            "circuit_breaker.state_after": breaker.state.value,
                        }
                    )
                    self._record_metric(
                        candidate,
                        succeeded=True,
                        prompt_tokens=pt,
                        completion_tokens=ct,
                        latency_ms=latency_ms,
                        fallback_used=is_fallback,
                    )
                    return GatewayResponse(
                        content=reply,
                        provider=candidate,
                        model=returned_model,
                        prompt_tokens=pt,
                        completion_tokens=ct,
                        total_tokens=tt,
                        latency_ms=latency_ms,
                        fallback_used=is_fallback,
                        trace_id=trace_id,
                    )
                except (CircuitBreakerOpenError, ModelCallFailure) as exc:
                    latency_ms = round((time.perf_counter() - started) * 1000, 3)
                    span.set_attribute("circuit_breaker.state_after", breaker.state.value)
                    span.record_exception(exc)
                    self._record_metric(candidate, succeeded=False, latency_ms=latency_ms)
                    last_error = exc
                    last_reason = getattr(exc, "code", type(exc).__name__)
                    if isinstance(exc, ModelCallFailure) and not exc.retryable and exc.attempted:
                        # Non-retryable 4xx protocol/auth rejection should not silently mask errors
                        if active_degrade_hook is None:
                            raise
                    continue

        if active_degrade_hook is not None:
            started = time.perf_counter()
            degraded_reply = active_degrade_hook(normalized_messages, last_reason)
            latency_ms = round((time.perf_counter() - started) * 1000, 3)
            pt, ct, tt = _extract_usage({}, normalized_messages, degraded_reply)
            self._record_metric(
                "rule_degraded",
                succeeded=True,
                prompt_tokens=pt,
                completion_tokens=ct,
                latency_ms=latency_ms,
                fallback_used=True,
                degraded=True,
            )
            return GatewayResponse(
                content=degraded_reply,
                provider="rule_degraded",
                model="rule_fallback",
                prompt_tokens=pt,
                completion_tokens=ct,
                total_tokens=tt,
                latency_ms=latency_ms,
                fallback_used=True,
                degraded=True,
                degrade_reason=last_reason,
                trace_id=trace_id,
            )

        if last_error is not None:
            raise last_error
        raise ModelCallFailure("MODEL_UNAVAILABLE", retryable=True)

    def invoke(
        self,
        messages_or_prompt: str | Sequence[Mapping[str, Any]],
        **kwargs: Any,
    ) -> GatewayResponse:
        """Synchronous gateway invocation compatible with both sync and async callers."""
        if isinstance(messages_or_prompt, str):
            messages: list[Mapping[str, Any]] = [{"role": "user", "content": messages_or_prompt}]
        else:
            messages = list(messages_or_prompt)

        try:
            asyncio.get_running_loop()
            has_running_loop = True
        except RuntimeError:
            has_running_loop = False

        if not has_running_loop:
            return asyncio.run(self.invoke_async(messages, **kwargs))

        ctx = contextvars.copy_context()
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(ctx.run, lambda: asyncio.run(self.invoke_async(messages, **kwargs)))
            return future.result()

    def generate(self, prompt: str, **kwargs: Any) -> str:
        """Backward-compatible synchronous text generation entrypoint."""
        settings = get_settings()
        started = time.perf_counter()
        logger.info(
            "LLM 生成开始 trace=%s provider=%s model=%s promptChars=%d",
            get_trace_id() or "-",
            self.provider,
            self.model or "-",
            len(prompt or ""),
        )
        if settings.llm_log_payload:
            logger.info("LLM 生成入参 trace=%s prompt=%s", get_trace_id() or "-", prompt)
        try:
            result = self.invoke(prompt, **kwargs)
        except Exception as exc:  # noqa: BLE001
            logger.error(
                "LLM 生成失败 trace=%s provider=%s model=%s latencyMs=%d error=%s: %s",
                get_trace_id() or "-",
                self.provider,
                self.model or "-",
                int((time.perf_counter() - started) * 1000),
                type(exc).__name__,
                exc,
            )
            raise
        if settings.llm_log_payload:
            logger.info("LLM 生成出参 trace=%s reply=%s", get_trace_id() or "-", result.content)
        logger.info(
            "LLM 生成完成 trace=%s provider=%s model=%s latencyMs=%d replyChars=%d tokens=%d",
            get_trace_id() or "-",
            result.provider,
            result.model or "-",
            int((time.perf_counter() - started) * 1000),
            len(result.content or ""),
            result.total_tokens,
        )
        return result.content


LLMGateway = ModelGateway
gateway = LLMGateway()
