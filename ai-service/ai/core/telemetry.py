"""OpenTelemetry + W3C Trace Context + Langfuse LLMOps telemetry with PII scrubbing."""

from __future__ import annotations

from collections.abc import Iterable, Mapping, MutableMapping, Sequence
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
from datetime import datetime, timezone
import json
import logging
import os
from pathlib import Path
import re
import secrets
import threading
import time
from typing import Any, Protocol
import uuid

import httpx
from opentelemetry import trace as otel_trace
from opentelemetry.trace import (
    Link as OTelLink,
    NonRecordingSpan,
    SpanContext as OTelSpanContext,
    SpanKind as OTelSpanKind,
    Status as OTelStatus,
    StatusCode as OTelStatusCode,
    TraceFlags as OTelTraceFlags,
)

from .trace import TRACE_ID_HEADER, TRACEPARENT_HEADER, get_trace_id

logger = logging.getLogger(__name__)

REDACTED_VALUE = "[REDACTED]"

KEY_EXECUTION_NODES: tuple[str, ...] = (
    "http.request",
    "router.decide",
    "context.build",
    "rag.retrieve",
    "rag.rerank",
    "agent.run",
    "agent.step",
    "model.invoke",
    "tool.execute",
    "hitl.approval",
)

_TRACEPARENT_RE = re.compile(
    r"^\s*([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})\s*$"
)
_SENSITIVE_KEY_RE = re.compile(
    r"(secret|token|password|passwd|api[-_]?key|authorization|cookie|credential|private[-_]?key)",
    re.IGNORECASE,
)
_SAFE_NUMERIC_TOKEN_KEY_RE = re.compile(
    r"(?:^|\.)(?:prompt|completion|total|input|output|max|max_input|max_output|reserved|reserved_output|estimated|cached)_tokens$",
    re.IGNORECASE,
)
_BEARER_VALUE_RE = re.compile(r"(?i)\b(Bearer)\s+[A-Za-z0-9._\-+/=]{6,}")
_API_KEY_VALUE_RE = re.compile(r"\bsk-[A-Za-z0-9_-]{12,}\b")
_CN_PHONE_RE = re.compile(r"(?<!\d)(\+?86[-\s]?)?(1[3-9]\d)\d{4}(\d{4})(?!\d)")

_OTEL_KIND_MAP: dict[str, OTelSpanKind] = {
    "INTERNAL": OTelSpanKind.INTERNAL,
    "SERVER": OTelSpanKind.SERVER,
    "CLIENT": OTelSpanKind.CLIENT,
    "PRODUCER": OTelSpanKind.PRODUCER,
    "CONSUMER": OTelSpanKind.CONSUMER,
}


@dataclass(frozen=True)
class TraceParent:
    """Parsed W3C Trace Context traceparent representation."""

    version: str
    trace_id: str
    span_id: str
    trace_flags: str = "01"

    @property
    def sampled(self) -> bool:
        return (int(self.trace_flags, 16) & 0x01) == 1

    def to_header(self) -> str:
        return format_traceparent(self.trace_id, self.span_id, sampled=self.sampled)


def generate_trace_id() -> str:
    """Generate a valid W3C 32-hex non-zero trace_id."""
    while True:
        candidate = secrets.token_hex(16)
        if candidate != "0" * 32:
            return candidate


def generate_span_id() -> str:
    """Generate a valid W3C 16-hex non-zero span_id."""
    while True:
        candidate = secrets.token_hex(8)
        if candidate != "0" * 16:
            return candidate


def parse_traceparent(header_value: str | None) -> TraceParent | None:
    """Parse and validate a W3C traceparent header (`00-<32hex>-<16hex>-<2hex>`)."""
    if not isinstance(header_value, str) or not header_value:
        return None
    match = _TRACEPARENT_RE.match(header_value.lower() if header_value.strip().startswith("00-") else header_value)
    if match is None:
        # Strict W3C spec requires lowercase hex
        return None
    if header_value.strip() != header_value.strip().lower():
        return None
    version, trace_id, span_id, flags = match.groups()
    if version == "ff" or version != "00":
        return None
    if trace_id == "0" * 32 or span_id == "0" * 16:
        return None
    return TraceParent(version=version, trace_id=trace_id, span_id=span_id, trace_flags=flags)


def format_traceparent(trace_id: str, span_id: str, *, sampled: bool = True) -> str:
    """Format a W3C traceparent header string."""
    tid = (trace_id or "").strip().lower()
    sid = (span_id or "").strip().lower()
    if len(tid) != 32 or tid == "0" * 32 or any(c not in "0123456789abcdef" for c in tid):
        raise ValueError("Invalid W3C trace_id")
    if len(sid) != 16 or sid == "0" * 16 or any(c not in "0123456789abcdef" for c in sid):
        raise ValueError("Invalid W3C span_id")
    flags = "01" if sampled else "00"
    return f"00-{tid}-{sid}-{flags}"


def mask_pii_text(text: str) -> str:
    """Mask raw phone numbers and inline bearer/API credentials inside string values."""
    if not text:
        return text
    masked = _BEARER_VALUE_RE.sub(r"\1 [REDACTED]", text)
    masked = _API_KEY_VALUE_RE.sub(REDACTED_VALUE, masked)
    masked = _CN_PHONE_RE.sub(
        lambda m: f"{m.group(1) or ''}{m.group(2)}****{m.group(3)}",
        masked,
    )
    return masked


def _is_sensitive_key(key: str, value: Any) -> bool:
    if not _SENSITIVE_KEY_RE.search(key):
        return False
    if (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and _SAFE_NUMERIC_TOKEN_KEY_RE.search(key)
    ):
        return False
    return True


def _redact_value(value: Any, *, depth: int = 0) -> Any:
    if depth > 8:
        return "[TRUNCATED]"
    if isinstance(value, str):
        return mask_pii_text(value)
    if isinstance(value, Mapping):
        sanitized: dict[str, Any] = {}
        for k, v in value.items():
            key_str = str(k)
            if _is_sensitive_key(key_str, v):
                sanitized[key_str] = REDACTED_VALUE
            else:
                sanitized[key_str] = _redact_value(v, depth=depth + 1)
        return sanitized
    if isinstance(value, (list, tuple)):
        return [_redact_value(item, depth=depth + 1) for item in value]
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    return mask_pii_text(str(value))


def redact_attributes(attributes: Mapping[str, Any] | None) -> dict[str, Any]:
    """Return a deep-copied attribute dictionary with secrets scrubbed and phones masked."""
    if not attributes:
        return {}
    result: dict[str, Any] = {}
    for raw_key, raw_val in attributes.items():
        key = str(raw_key)
        if _is_sensitive_key(key, raw_val):
            result[key] = REDACTED_VALUE
        else:
            result[key] = _redact_value(raw_val)
    return result


@dataclass(frozen=True)
class SpanLink:
    """Causal link across async boundaries (Outbox -> Worker, HITL pause -> resume)."""

    trace_id: str
    span_id: str
    attributes: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        object.__setattr__(self, "trace_id", self.trace_id.strip().lower())
        object.__setattr__(self, "span_id", self.span_id.strip().lower())
        object.__setattr__(self, "attributes", redact_attributes(self.attributes))

    @classmethod
    def from_traceparent(
        cls,
        traceparent: str,
        *,
        attributes: Mapping[str, Any] | None = None,
    ) -> SpanLink:
        parsed = parse_traceparent(traceparent)
        if parsed is None:
            raise ValueError("Invalid traceparent for SpanLink")
        return cls(
            trace_id=parsed.trace_id,
            span_id=parsed.span_id,
            attributes=redact_attributes(attributes),
        )

    @property
    def traceparent(self) -> str:
        return format_traceparent(self.trace_id, self.span_id)

    def to_dict(self) -> dict[str, Any]:
        return {
            "trace_id": self.trace_id,
            "span_id": self.span_id,
            "traceparent": self.traceparent,
            "attributes": dict(self.attributes),
        }


@dataclass
class RecordedSpan:
    """Recorded execution span with W3C context, links, timing, and redacted attributes."""

    trace_id: str
    span_id: str
    parent_span_id: str | None
    name: str
    kind: str
    start_ns: int
    end_ns: int = 0
    duration_ms: float = 0.0
    status: str = "OK"
    status_description: str | None = None
    attributes: dict[str, Any] = field(default_factory=dict)
    links: list[SpanLink] = field(default_factory=list)
    events: list[dict[str, Any]] = field(default_factory=list)
    request_id: str = ""

    @property
    def traceparent(self) -> str:
        return format_traceparent(self.trace_id, self.span_id, sampled=True)

    def set_attribute(self, key: str, value: Any) -> None:
        redacted = redact_attributes({key: value})
        self.attributes.update(redacted)

    def set_attributes(self, values: Mapping[str, Any]) -> None:
        self.attributes.update(redact_attributes(values))

    def add_event(
        self,
        name: str,
        attributes: Mapping[str, Any] | None = None,
    ) -> None:
        self.events.append(
            {
                "name": str(name),
                "timestamp_ns": time.time_ns(),
                "attributes": redact_attributes(attributes),
            }
        )

    def set_status(self, status: str, description: str | None = None) -> None:
        normalized = status.strip().upper()
        self.status = "ERROR" if normalized == "ERROR" else "OK"
        self.status_description = mask_pii_text(description) if description else None

    def record_exception(self, exc: BaseException) -> None:
        desc = mask_pii_text(f"{type(exc).__name__}: {exc}")
        self.set_status("ERROR", desc)
        self.add_event(
            "exception",
            {
                "exception.type": type(exc).__name__,
                "exception.message": mask_pii_text(str(exc)),
            },
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "trace_id": self.trace_id,
            "span_id": self.span_id,
            "parent_span_id": self.parent_span_id,
            "traceparent": self.traceparent,
            "request_id": self.request_id,
            "name": self.name,
            "kind": self.kind,
            "start_ns": self.start_ns,
            "end_ns": self.end_ns,
            "duration_ms": self.duration_ms,
            "status": self.status,
            "status_description": self.status_description,
            "attributes": dict(self.attributes),
            "links": [link.to_dict() for link in self.links],
            "events": list(self.events),
        }


class SpanExporterProtocol(Protocol):
    """Protocol implemented by telemetry span exporters."""

    def export(self, spans: Sequence[RecordedSpan]) -> None: ...


class InMemorySpanExporter:
    """Thread-safe in-memory span store with optional JSON file dump for offline/CI runs."""

    def __init__(self, *, max_spans: int = 10_000, json_path: str | Path | None = None) -> None:
        self.max_spans = max(1, int(max_spans))
        self.json_path = Path(json_path) if json_path is not None else None
        self._lock = threading.RLock()
        self._spans: list[RecordedSpan] = []

    def export(self, spans: Sequence[RecordedSpan]) -> None:
        if not spans:
            return
        with self._lock:
            self._spans.extend(spans)
            if len(self._spans) > self.max_spans:
                self._spans = self._spans[-self.max_spans :]
            snapshot = [s.to_dict() for s in self._spans]
        if self.json_path is not None:
            self.json_path.parent.mkdir(parents=True, exist_ok=True)
            self.json_path.write_text(
                json.dumps(snapshot, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )

    def get_spans(self) -> list[RecordedSpan]:
        with self._lock:
            return list(self._spans)

    def get_span_dicts(self) -> list[dict[str, Any]]:
        with self._lock:
            return [s.to_dict() for s in self._spans]

    def clear(self) -> None:
        with self._lock:
            self._spans.clear()


def _ns_to_iso8601(ts_ns: int) -> str:
    dt = datetime.fromtimestamp(ts_ns / 1_000_000_000, tz=timezone.utc)
    return dt.isoformat().replace("+00:00", "Z")


class LangfuseIngestionExporter:
    """Bounded Langfuse `/api/public/ingestion` batch exporter with zero-network offline mode."""

    def __init__(
        self,
        *,
        public_key: str | None = None,
        secret_key: str | None = None,
        base_url: str | None = None,
        batch_size: int = 20,
        timeout_seconds: float = 5.0,
        transport: httpx.BaseTransport | None = None,
        offline_fallback: InMemorySpanExporter | None = None,
    ) -> None:
        pub = public_key if public_key is not None else os.getenv("LANGFUSE_PUBLIC_KEY", "")
        sec = secret_key if secret_key is not None else os.getenv("LANGFUSE_SECRET_KEY", "")
        host = (
            base_url
            or os.getenv("LANGFUSE_BASE_URL")
            or os.getenv("LANGFUSE_HOST")
            or "https://cloud.langfuse.com"
        )
        self.public_key = pub.strip()
        self.secret_key = sec.strip()
        self.base_url = host.rstrip("/")
        self.batch_size = max(1, int(batch_size))
        self.timeout_seconds = max(0.2, float(timeout_seconds))
        self._transport = transport
        self.offline_fallback = offline_fallback or InMemorySpanExporter()
        self._lock = threading.RLock()
        self._pending: list[dict[str, Any]] = []
        self.exported_batches = 0
        self.exported_events = 0
        self.failed_batches = 0

    @property
    def is_configured(self) -> bool:
        return bool(self.public_key and self.secret_key)

    def _to_langfuse_events(self, span: RecordedSpan) -> list[dict[str, Any]]:
        events: list[dict[str, Any]] = []
        start_iso = _ns_to_iso8601(span.start_ns)
        end_iso = _ns_to_iso8601(span.end_ns or span.start_ns)
        attrs = redact_attributes(span.attributes)
        links_meta = [link.to_dict() for link in span.links]
        metadata = {
            **attrs,
            "span_kind": span.kind,
            "request_id": span.request_id,
            "duration_ms": span.duration_ms,
        }
        if links_meta:
            metadata["span_links"] = links_meta

        if span.parent_span_id is None:
            events.append(
                {
                    "id": str(uuid.uuid4()),
                    "type": "trace-create",
                    "timestamp": start_iso,
                    "body": {
                        "id": span.trace_id,
                        "name": span.name,
                        "timestamp": start_iso,
                        "metadata": metadata,
                    },
                }
            )

        is_generation = span.name == "model.invoke" or "llm.model" in attrs or "gen_ai.request.model" in attrs
        if is_generation:
            usage: dict[str, Any] = {}
            for src_key, dst_key in (
                ("llm.prompt_tokens", "input"),
                ("llm.completion_tokens", "output"),
                ("llm.total_tokens", "total"),
            ):
                if isinstance(attrs.get(src_key), int):
                    usage[dst_key] = attrs[src_key]
            events.append(
                {
                    "id": str(uuid.uuid4()),
                    "type": "generation-create",
                    "timestamp": end_iso,
                    "body": {
                        "id": span.span_id,
                        "traceId": span.trace_id,
                        "parentObservationId": span.parent_span_id,
                        "name": span.name,
                        "startTime": start_iso,
                        "endTime": end_iso,
                        "model": attrs.get("llm.model") or attrs.get("gen_ai.request.model"),
                        "usage": usage or None,
                        "level": "ERROR" if span.status == "ERROR" else "DEFAULT",
                        "statusMessage": span.status_description,
                        "metadata": metadata,
                    },
                }
            )
        else:
            events.append(
                {
                    "id": str(uuid.uuid4()),
                    "type": "span-create",
                    "timestamp": end_iso,
                    "body": {
                        "id": span.span_id,
                        "traceId": span.trace_id,
                        "parentObservationId": span.parent_span_id,
                        "name": span.name,
                        "startTime": start_iso,
                        "endTime": end_iso,
                        "level": "ERROR" if span.status == "ERROR" else "DEFAULT",
                        "statusMessage": span.status_description,
                        "metadata": metadata,
                    },
                }
            )
        return events

    def export(self, spans: Sequence[RecordedSpan]) -> None:
        if not spans:
            return
        if not self.is_configured:
            # Strictly offline when credentials are absent: zero network I/O.
            self.offline_fallback.export(spans)
            return

        batch_to_send: list[dict[str, Any]] | None = None
        with self._lock:
            for span in spans:
                self._pending.extend(self._to_langfuse_events(span))
            if len(self._pending) >= self.batch_size:
                batch_to_send = self._pending[:]
                self._pending.clear()
        if batch_to_send:
            self._send_batch(batch_to_send)

    def flush(self) -> bool:
        """Flush any buffered Langfuse ingestion events immediately."""
        if not self.is_configured:
            return True
        with self._lock:
            if not self._pending:
                return True
            batch_to_send = self._pending[:]
            self._pending.clear()
        return self._send_batch(batch_to_send)

    def _send_batch(self, events: list[dict[str, Any]]) -> bool:
        payload = {
            "batch": events,
            "metadata": {"sdk_integration": "supportflow-ai-telemetry"},
        }
        try:
            with httpx.Client(
                transport=self._transport,
                trust_env=False,
                follow_redirects=False,
                timeout=httpx.Timeout(self.timeout_seconds, connect=min(2.0, self.timeout_seconds)),
            ) as client:
                response = client.post(
                    f"{self.base_url}/api/public/ingestion",
                    auth=(self.public_key, self.secret_key),
                    headers={"Content-Type": "application/json"},
                    content=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                )
            if 200 <= response.status_code < 300:
                with self._lock:
                    self.exported_batches += 1
                    self.exported_events += len(events)
                return True
            with self._lock:
                self.failed_batches += 1
            logger.warning("Langfuse ingestion non-2xx status=%d", response.status_code)
            return False
        except Exception as exc:  # noqa: BLE001
            with self._lock:
                self.failed_batches += 1
            logger.warning("Langfuse ingestion export failed: %s", type(exc).__name__)
            return False


class SpanRecorder:
    """Thread-safe and contextvar-safe recorder coordinating in-memory, OTel SDK, and Langfuse."""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self.memory_exporter = InMemorySpanExporter()
        self.langfuse_exporter = LangfuseIngestionExporter(offline_fallback=InMemorySpanExporter(max_spans=1))
        self._exporters: list[SpanExporterProtocol] = [self.memory_exporter, self.langfuse_exporter]

    def add_exporter(self, exporter: SpanExporterProtocol) -> None:
        with self._lock:
            self._exporters.append(exporter)

    def remove_exporter(self, exporter: SpanExporterProtocol) -> None:
        with self._lock:
            self._exporters = [item for item in self._exporters if item is not exporter]

    def record(self, span: RecordedSpan) -> None:
        with self._lock:
            exporters = list(self._exporters)
        for exporter in exporters:
            try:
                exporter.export([span])
            except Exception as exc:  # noqa: BLE001
                logger.warning("Span exporter %s failed: %s", type(exporter).__name__, type(exc).__name__)

    def get_spans(self) -> list[RecordedSpan]:
        return self.memory_exporter.get_spans()

    def clear(self) -> None:
        self.memory_exporter.clear()


_current_span: ContextVar[RecordedSpan | None] = ContextVar("supportflow_current_span", default=None)
_default_recorder = SpanRecorder()


def get_span_recorder() -> SpanRecorder:
    """Return the global SpanRecorder instance."""
    return _default_recorder


def get_current_span() -> RecordedSpan | None:
    """Return the currently active RecordedSpan in this async/thread context."""
    return _current_span.get()


def current_traceparent() -> str | None:
    """Return active W3C traceparent string if inside an active span."""
    active = _current_span.get()
    return active.traceparent if active is not None else None


def extract_trace_context(headers: Mapping[str, str] | None) -> TraceParent | None:
    """Extract W3C TraceParent from inbound HTTP/message headers (case-insensitive)."""
    if not headers:
        return None
    for key, value in headers.items():
        if str(key).lower() == TRACEPARENT_HEADER:
            return parse_traceparent(value)
    return None


def inject_trace_headers(
    headers: MutableMapping[str, str] | None = None,
    *,
    request_id: str | None = None,
) -> dict[str, str]:
    """Inject W3C traceparent (and X-Request-Id when present) into outbound headers."""
    out: dict[str, str] = dict(headers) if headers is not None else {}
    tp = current_traceparent()
    if tp:
        out[TRACEPARENT_HEADER] = tp
    req_id = (request_id if request_id is not None else get_trace_id()).strip()
    if req_id and TRACE_ID_HEADER not in out:
        out[TRACE_ID_HEADER] = req_id
    return out


def _normalize_links(links: Sequence[SpanLink | Mapping[str, Any] | str] | None) -> list[SpanLink]:
    if not links:
        return []
    normalized: list[SpanLink] = []
    for item in links:
        if isinstance(item, SpanLink):
            normalized.append(item)
        elif isinstance(item, str):
            parsed = parse_traceparent(item)
            if parsed is not None:
                normalized.append(SpanLink(trace_id=parsed.trace_id, span_id=parsed.span_id))
        elif isinstance(item, Mapping):
            tp = item.get("traceparent")
            attrs = item.get("attributes") if isinstance(item.get("attributes"), Mapping) else {}
            if isinstance(tp, str):
                parsed = parse_traceparent(tp)
                if parsed is not None:
                    normalized.append(
                        SpanLink(trace_id=parsed.trace_id, span_id=parsed.span_id, attributes=attrs)
                    )
                    continue
            tid = str(item.get("trace_id") or "")
            sid = str(item.get("span_id") or "")
            if len(tid) == 32 and len(sid) == 16:
                normalized.append(SpanLink(trace_id=tid, span_id=sid, attributes=attrs))
    return normalized


def _build_otel_links(links: Sequence[SpanLink]) -> list[OTelLink]:
    otel_links: list[OTelLink] = []
    for link in links:
        try:
            ctx = OTelSpanContext(
                trace_id=int(link.trace_id, 16),
                span_id=int(link.span_id, 16),
                is_remote=True,
                trace_flags=OTelTraceFlags(0x01),
            )
            otel_links.append(OTelLink(ctx, attributes=link.attributes))
        except ValueError:
            continue
    return otel_links


@contextmanager
def start_span(
    name: str,
    *,
    kind: str = "INTERNAL",
    attributes: Mapping[str, Any] | None = None,
    links: Sequence[SpanLink | Mapping[str, Any] | str] | None = None,
    parent_traceparent: str | None = None,
    recorder: SpanRecorder | None = None,
):
    """Start a contextvar-safe telemetry span bridged to OpenTelemetry API and SpanRecorder."""
    normalized_kind = (kind or "INTERNAL").strip().upper()
    if normalized_kind not in _OTEL_KIND_MAP:
        normalized_kind = "INTERNAL"

    parent_span = _current_span.get()
    parsed_parent = parse_traceparent(parent_traceparent) if parent_traceparent else None

    if parsed_parent is not None:
        trace_id = parsed_parent.trace_id
        parent_span_id: str | None = parsed_parent.span_id
    elif parent_span is not None:
        trace_id = parent_span.trace_id
        parent_span_id = parent_span.span_id
    else:
        trace_id = generate_trace_id()
        parent_span_id = None

    span_id = generate_span_id()
    normalized_links = _normalize_links(links)
    redacted_attrs = redact_attributes(attributes)
    req_id = get_trace_id()
    if req_id and "request.id" not in redacted_attrs:
        redacted_attrs["request.id"] = req_id

    start_wall_ns = time.time_ns()
    start_mono_ns = time.perf_counter_ns()
    recorded = RecordedSpan(
        trace_id=trace_id,
        span_id=span_id,
        parent_span_id=parent_span_id,
        name=str(name),
        kind=normalized_kind,
        start_ns=start_wall_ns,
        attributes=redacted_attrs,
        links=normalized_links,
        request_id=req_id,
    )

    tracer = otel_trace.get_tracer("supportflow.ai")
    otel_parent_ctx = None
    if parsed_parent is not None:
        remote_ctx = OTelSpanContext(
            trace_id=int(parsed_parent.trace_id, 16),
            span_id=int(parsed_parent.span_id, 16),
            is_remote=True,
            trace_flags=OTelTraceFlags(0x01 if parsed_parent.sampled else 0x00),
        )
        otel_parent_ctx = otel_trace.set_span_in_context(NonRecordingSpan(remote_ctx))

    token = _current_span.set(recorded)
    target_recorder = recorder or _default_recorder
    try:
        with tracer.start_as_current_span(
            recorded.name,
            context=otel_parent_ctx,
            kind=_OTEL_KIND_MAP[normalized_kind],
            links=_build_otel_links(normalized_links),
        ) as otel_span:
            try:
                yield recorded
            except BaseException as exc:
                recorded.record_exception(exc)
                if otel_span.is_recording():
                    otel_span.set_status(OTelStatus(OTelStatusCode.ERROR, recorded.status_description or ""))
                raise
            finally:
                if otel_span.is_recording():
                    for k, v in recorded.attributes.items():
                        if isinstance(v, (str, bool, int, float)):
                            otel_span.set_attribute(k, v)
                    if recorded.status == "OK":
                        otel_span.set_status(OTelStatus(OTelStatusCode.OK))
    finally:
        _current_span.reset(token)
        elapsed_ns = max(0, time.perf_counter_ns() - start_mono_ns)
        recorded.end_ns = start_wall_ns + elapsed_ns
        recorded.duration_ms = round(elapsed_ns / 1_000_000, 3)
        target_recorder.record(recorded)


def configure_otel_sdk(
    *,
    service_name: str = "supportflow-ai",
    extra_span_processors: Sequence[Any] | None = None,
) -> bool:
    """Attach opentelemetry-sdk TracerProvider seamlessly when installed."""
    try:
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk.trace import TracerProvider
    except ImportError:
        return False

    provider = TracerProvider(resource=Resource.create({"service.name": service_name}))
    for processor in extra_span_processors or ():
        provider.add_span_processor(processor)
    otel_trace.set_tracer_provider(provider)
    return True


def verify_key_node_coverage(
    spans: Iterable[RecordedSpan | Mapping[str, Any]],
    required_nodes: Sequence[str] | None = None,
) -> dict[str, Any]:
    """Audit whether recorded spans cover 100% of required execution nodes."""
    required = list(required_nodes) if required_nodes is not None else list(KEY_EXECUTION_NODES)
    normalized: list[dict[str, Any]] = []
    for item in spans:
        if isinstance(item, RecordedSpan):
            normalized.append(item.to_dict())
        elif isinstance(item, Mapping):
            normalized.append(dict(item))

    observed_names = {str(s.get("name") or "") for s in normalized}
    span_ids = {str(s.get("span_id") or "") for s in normalized if s.get("span_id")}
    trace_ids = {str(s.get("trace_id") or "") for s in normalized if s.get("trace_id")}

    covered_nodes = [node for node in required if node in observed_names]
    missing_nodes = [node for node in required if node not in observed_names]

    broken_parent_refs: list[str] = []
    for s in normalized:
        parent_id = s.get("parent_span_id")
        # Root spans or external incoming remote parents won't be in local span_ids only when HTTP entry
        if (
            parent_id
            and parent_id not in span_ids
            and s.get("name") != "http.request"
        ):
            broken_parent_refs.append(str(s.get("span_id") or s.get("name")))

    coverage_rate = round(len(covered_nodes) / len(required), 4) if required else 1.0
    return {
        "complete": len(missing_nodes) == 0,
        "coverage_rate": coverage_rate,
        "required_nodes": required,
        "covered_nodes": covered_nodes,
        "missing_nodes": missing_nodes,
        "trace_ids": sorted(trace_ids),
        "broken_parent_refs": broken_parent_refs,
        "span_count": len(normalized),
    }
