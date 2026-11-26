"""调用链追踪：透传 X-Request-Id 与 W3C traceparent，让 Java 调用方与 AI 服务全链路对齐。"""

from __future__ import annotations

import uuid
from collections.abc import MutableMapping
from contextlib import contextmanager
from contextvars import ContextVar

from fastapi import Header

TRACE_ID_HEADER = "X-Request-Id"
TRACEPARENT_HEADER = "traceparent"

_trace_id: ContextVar[str] = ContextVar("trace_id", default="")


def set_trace_id(trace_id: str) -> None:
    _trace_id.set(trace_id)


def get_trace_id() -> str:
    return _trace_id.get()


@contextmanager
def bind_trace_id(trace_id: str):
    """Scope one background task without retaining its ID on a reused worker."""
    token = _trace_id.set(trace_id)
    try:
        yield
    finally:
        _trace_id.reset(token)


def new_trace_id() -> str:
    """调用方没带请求号时自行生成，保证日志里始终有 trace 可查。"""
    return uuid.uuid4().hex[:16]


def get_current_traceparent() -> str:
    """Return active W3C traceparent header string if a telemetry span is active."""
    from .telemetry import current_traceparent

    return current_traceparent() or ""


def outbound_trace_headers(
    headers: MutableMapping[str, str] | None = None,
) -> dict[str, str]:
    """Build outbound HTTP/event headers carrying X-Request-Id and W3C traceparent."""
    from .telemetry import inject_trace_headers

    return inject_trace_headers(headers, request_id=get_trace_id() or None)


async def require_trace_id(
    x_request_id: str | None = Header(default=None, alias=TRACE_ID_HEADER),
) -> str:
    """依赖注入：解析调用方请求号（缺省自动生成）。"""
    trace_id = (x_request_id or "").strip() or new_trace_id()
    set_trace_id(trace_id)
    return trace_id
