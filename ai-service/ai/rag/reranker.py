"""候选切片重排（Reranker）：支持远程千问 gte-rerank 与确定性本地混合重排兜底。

在线检索流程：
    hybrid_search (candidate_depth=15, RRF 融合)
      ↓
    rerank_candidates (gte-rerank 交叉编码重排 / lexical_hybrid 确定性重排)
      ↓
    top_k 高区分度证据切片（含来源权威度治理优先级）
"""

from __future__ import annotations

import json
import logging
import math
import re
import time
from typing import Any

import httpx

from ..config import get_settings
from ..core.trace import get_trace_id
from .store import FUNCTION_CHARS, normalize_source_authority, query_terms

logger = logging.getLogger(__name__)

VALID_RERANK_MODES = ("auto", "qwen", "lexical_hybrid")
AUTHORITY_BONUS = {
    "official": 0.055,
    "reference": 0.015,
    "community": -0.045,
}
AUTHORITY_ORDER = {
    "official": 3,
    "reference": 2,
    "community": 1,
}

_ASCII_IDENTIFIER = re.compile(r"[a-z0-9]+(?:[-_+/][a-z0-9]+)*")
_CJK_RUN = re.compile(r"[\u4e00-\u9fff]+")
_WINDOW_CHARS = 90
_WINDOW_STRIDE = 30
_MAX_DOC_CHARS = 2400


class RerankCallError(RuntimeError):
    """Controlled failure when calling an external reranking provider."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


def _extract_ascii_identifiers(text: str) -> list[str]:
    """Extract technical acronyms, error codes, and hyphenated identifiers."""
    seen: set[str] = set()
    tokens: list[str] = []
    for raw in _ASCII_IDENTIFIER.findall((text or "").lower()):
        cleaned = raw.strip("-_+/")
        if len(cleaned) < 2:
            continue
        if cleaned not in seen:
            seen.add(cleaned)
            tokens.append(cleaned)
    return tokens[:16]


def _extract_cjk_phrases(text: str) -> list[str]:
    """Extract informative CJK 3-grams and 4-grams for phrase contiguity scoring."""
    phrases: list[str] = []
    seen: set[str] = set()
    for run in _CJK_RUN.findall(text or ""):
        trimmed = run.strip("".join(FUNCTION_CHARS))
        source = trimmed if len(trimmed) >= 3 else run
        for size in (4, 3):
            if len(source) < size:
                continue
            for idx in range(len(source) - size + 1):
                gram = source[idx:idx + size]
                if sum(ch not in FUNCTION_CHARS for ch in gram) < 2:
                    continue
                if gram not in seen:
                    seen.add(gram)
                    phrases.append(gram)
    return phrases[:24]


def _window_density(content_lower: str, terms: list[str]) -> float:
    """Measure peak concentration of matched query terms within a local sentence window."""
    if not terms or not content_lower:
        return 0.0
    if len(content_lower) <= _WINDOW_CHARS:
        matched = sum(1 for term in terms if term in content_lower)
        return matched / len(terms)
    best = 0
    limit = len(content_lower) - _WINDOW_CHARS + 1
    for start in range(0, limit, _WINDOW_STRIDE):
        window = content_lower[start:start + _WINDOW_CHARS]
        matched = sum(1 for term in terms if term in window)
        if matched > best:
            best = matched
            if best == len(terms):
                break
    tail = content_lower[-_WINDOW_CHARS:]
    best = max(best, sum(1 for term in terms if term in tail))
    return best / len(terms)


def _ascii_token_present(token: str, hay_tokens: set[str], hay_lower: str) -> bool:
    if token in hay_tokens:
        return True
    if any(sep in token for sep in "-_+/") and token in hay_lower:
        return True
    return False


def _score_lexical_candidate(
    query_lower: str,
    terms: list[str],
    ascii_ids: list[str],
    cjk_phrases: list[str],
    candidate: dict[str, Any],
    rank_index: int,
) -> float:
    """Compute a bounded deterministic relevance score in [0.0, 1.0]."""
    content = str(candidate.get("content") or "")
    title = str(candidate.get("doc_title") or "")
    content_lower = content.lower()
    title_lower = title.lower()
    combined_lower = f"{title_lower}\n{content_lower}"

    content_cov = (
        sum(1 for term in terms if term in content_lower) / len(terms)
        if terms
        else 0.0
    )
    joint_cov = (
        sum(1 for term in terms if term in combined_lower) / len(terms)
        if terms
        else 0.0
    )
    title_cov = (
        sum(1 for term in terms if term in title_lower) / len(terms)
        if terms and title_lower
        else 0.0
    )

    doc_ascii = set(_extract_ascii_identifiers(combined_lower))
    title_ascii = set(_extract_ascii_identifiers(title_lower))
    if ascii_ids:
        ascii_hits = sum(
            1 for token in ascii_ids if _ascii_token_present(token, doc_ascii, combined_lower)
        )
        ascii_title_hits = sum(
            1 for token in ascii_ids if _ascii_token_present(token, title_ascii, title_lower)
        )
        ascii_score = (ascii_hits / len(ascii_ids)) * 0.8 + (ascii_title_hits / len(ascii_ids)) * 0.2
    else:
        ascii_score = joint_cov

    if cjk_phrases:
        phrase_hits = sum(1 for phrase in cjk_phrases if phrase in combined_lower)
        phrase_score = min(1.0, phrase_hits / max(1, min(len(cjk_phrases), 4)))
    else:
        phrase_score = 1.0 if len(query_lower) >= 4 and query_lower in combined_lower else content_cov

    density = _window_density(content_lower, terms)
    exact_query_bonus = 0.08 if len(query_lower) >= 4 and query_lower in combined_lower else 0.0

    # Mild rank/RRF prior preserves retrieval signal when lexical features tie.
    rrf_prior = min(1.0, float(candidate.get("rrf_score") or 0.0) * 30.0)
    if rrf_prior == 0.0:
        rrf_prior = 1.0 / (1.0 + rank_index * 0.25)

    authority = normalize_source_authority(candidate.get("source_authority"))
    authority_adj = AUTHORITY_BONUS.get(authority, 0.0)

    raw_score = (
        0.24 * content_cov
        + 0.24 * joint_cov
        + 0.16 * ascii_score
        + 0.14 * phrase_score
        + 0.10 * density
        + 0.07 * title_cov
        + 0.05 * rrf_prior
        + exact_query_bonus
        + authority_adj
    )
    return max(0.0, min(1.0, round(raw_score, 4)))


def _lexical_hybrid_rerank(query: str, candidates: list[dict], top_k: int) -> list[dict]:
    """Deterministic cross-feature reranker for offline operation and fallback."""
    cleaned_query = (query or "").strip()
    query_lower = cleaned_query.lower()
    terms = query_terms(cleaned_query, max_terms=24)
    ascii_ids = _extract_ascii_identifiers(cleaned_query)
    cjk_phrases = _extract_cjk_phrases(cleaned_query)

    scored: list[tuple[float, int, int, dict]] = []
    for idx, item in enumerate(candidates):
        authority = normalize_source_authority(item.get("source_authority"))
        score = _score_lexical_candidate(
            query_lower, terms, ascii_ids, cjk_phrases, item, idx
        )
        updated = dict(item)
        updated["source_authority"] = authority
        updated["rerank_score"] = score
        updated["score"] = score
        scored.append((score, AUTHORITY_ORDER[authority], -idx, updated))

    scored.sort(
        key=lambda entry: (
            -entry[0],
            -entry[1],
            -entry[2],
            int(entry[3].get("id") or 0),
        )
    )
    return [entry[3] for entry in scored[:top_k]]


def _format_rerank_document(candidate: dict[str, Any]) -> str:
    title = str(candidate.get("doc_title") or "").strip()
    content = str(candidate.get("content") or "").strip()
    text = f"{title}\n{content}".strip() if title else content
    return text[:_MAX_DOC_CHARS]


def _read_bounded_json(response: httpx.Response, max_bytes: int) -> dict[str, Any]:
    chunks: list[bytes] = []
    total = 0
    for chunk in response.iter_bytes():
        total += len(chunk)
        if total > max_bytes:
            raise RerankCallError("RERANK_RESPONSE_TOO_LARGE")
        chunks.append(chunk)
    try:
        data = json.loads(b"".join(chunks).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise RerankCallError("RERANK_RESPONSE_INVALID_JSON") from None
    if not isinstance(data, dict):
        raise RerankCallError("RERANK_RESPONSE_INVALID_SCHEMA")
    return data


def _call_qwen_rerank(
    query: str,
    candidates: list[dict],
    top_k: int,
    *,
    settings: Any,
    tenant_code: str = "",
    _transport: httpx.BaseTransport | None = None,
) -> tuple[list[dict], dict[str, Any]]:
    api_key = settings.rerank_key() if hasattr(settings, "rerank_key") else ""
    if not api_key:
        raise RerankCallError("RERANK_API_KEY_MISSING")

    url = getattr(
        settings,
        "rag_rerank_url",
        "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank",
    )
    model = getattr(settings, "rag_rerank_model", "gte-rerank") or "gte-rerank"
    timeout_sec = float(getattr(settings, "rag_rerank_timeout", 5.0) or 5.0)
    max_bytes = int(getattr(settings, "rag_rerank_max_response_bytes", 262144) or 262144)

    documents = [_format_rerank_document(item) for item in candidates]
    payload = {
        "model": model,
        "input": {
            "query": query,
            "documents": documents,
        },
        "parameters": {
            "return_documents": False,
            "top_n": min(top_k, len(candidates)),
        },
    }
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    timeout = httpx.Timeout(timeout_sec, connect=min(2.5, timeout_sec))
    started = time.perf_counter()
    try:
        with httpx.Client(timeout=timeout, transport=_transport, follow_redirects=False) as client:
            with client.stream("POST", url, headers=headers, json=payload) as response:
                if response.status_code != 200:
                    raise RerankCallError(f"RERANK_HTTP_{response.status_code}")
                data = _read_bounded_json(response, max_bytes)
    except httpx.TimeoutException:
        raise RerankCallError("RERANK_TIMEOUT") from None
    except httpx.HTTPError:
        raise RerankCallError("RERANK_TRANSPORT_ERROR") from None

    latency_ms = round((time.perf_counter() - started) * 1000, 3)
    output = data.get("output")
    items = output.get("results") if isinstance(output, dict) else None
    if not isinstance(items, list) or not items:
        raise RerankCallError("RERANK_OUTPUT_EMPTY")

    seen_indexes: set[int] = set()
    ranked_entries: list[tuple[float, int, int, dict]] = []
    for row in items:
        if not isinstance(row, dict):
            raise RerankCallError("RERANK_ITEM_INVALID")
        idx = row.get("index")
        raw_score = row.get("relevance_score")
        if (
            type(idx) is not int
            or not 0 <= idx < len(candidates)
            or idx in seen_indexes
            or type(raw_score) not in (int, float)
            or not math.isfinite(raw_score)
        ):
            raise RerankCallError("RERANK_ITEM_INVALID")
        seen_indexes.add(idx)
        authority = normalize_source_authority(candidates[idx].get("source_authority"))
        score = round(float(raw_score), 4)
        updated = dict(candidates[idx])
        updated["source_authority"] = authority
        updated["rerank_score"] = score
        updated["score"] = score
        ranked_entries.append((score, AUTHORITY_ORDER[authority], -idx, updated))

    # Quantize to 3 decimals for authority tie-break on near-equal neural scores.
    ranked_entries.sort(
        key=lambda entry: (
            -round(entry[0], 3),
            -entry[1],
            -entry[0],
            -entry[2],
        )
    )
    usage_raw = data.get("usage") if isinstance(data.get("usage"), dict) else {}
    total_tokens = usage_raw.get("total_tokens")
    usage = {
        "model": model,
        "total_tokens": total_tokens if type(total_tokens) is int and total_tokens >= 0 else None,
        "request_id": data.get("request_id") if isinstance(data.get("request_id"), str) else None,
        "latency_ms": latency_ms,
        "candidate_count": len(candidates),
    }
    logger.info(
        "远程重排完成 trace=%s tenant=%s model=%s candidates=%d topK=%d tokens=%s latencyMs=%.1f",
        get_trace_id() or "-",
        tenant_code or "-",
        model,
        len(candidates),
        top_k,
        usage["total_tokens"],
        latency_ms,
    )
    return [entry[3] for entry in ranked_entries[:top_k]], usage


def rerank_candidates(
    query: str,
    candidates: list[dict],
    top_k: int = 5,
    *,
    tenant_code: str = "",
    mode: str = "auto",
    _transport: httpx.BaseTransport | None = None,
) -> dict[str, Any]:
    """Rerank retrieved candidate chunks using Qwen gte-rerank or deterministic lexical_hybrid."""
    if mode not in VALID_RERANK_MODES:
        raise ValueError(f"Unsupported rerank mode: {mode}")
    limit = max(1, min(int(top_k), 50))
    cleaned = (query or "").strip()
    if not candidates or not cleaned:
        return {"results": [dict(item) for item in candidates[:limit]], "rerank_mode": "none", "usage": None}

    settings = get_settings()
    configured_mode = getattr(settings, "rag_rerank_mode", "auto") or "auto"
    effective_mode = configured_mode if mode == "auto" else mode
    has_key = bool(settings.rerank_key()) if hasattr(settings, "rerank_key") else False

    try_qwen = effective_mode == "qwen" or (effective_mode == "auto" and has_key)
    if try_qwen:
        try:
            results, usage = _call_qwen_rerank(
                cleaned,
                candidates,
                limit,
                settings=settings,
                tenant_code=tenant_code,
                _transport=_transport,
            )
            return {"results": results, "rerank_mode": "qwen", "usage": usage}
        except Exception as exc:  # noqa: BLE001
            reason = exc.code if isinstance(exc, RerankCallError) else type(exc).__name__
            logger.warning(
                "远程重排降级为本地确定性重排 trace=%s tenant=%s code=RAG_RERANK_FALLBACK reason=%s",
                get_trace_id() or "-",
                tenant_code or "-",
                reason,
            )
            results = _lexical_hybrid_rerank(cleaned, candidates, limit)
            return {
                "results": results,
                "rerank_mode": "lexical_hybrid",
                "usage": None,
                "fallback_reason": reason,
            }

    results = _lexical_hybrid_rerank(cleaned, candidates, limit)
    return {"results": results, "rerank_mode": "lexical_hybrid", "usage": None}
