"""Provider selection and bounded chat transport for legacy customer features."""

from __future__ import annotations

from dataclasses import dataclass, field
import logging
import re
import time
from typing import Any

from ..config import get_settings
from .db import connect
from .llm_usage import record_chat_usage
from .tenant import get_tenant_code
from .trace import get_trace_id
from .chat_transport import ModelCallFailure, _send_chat_json

logger = logging.getLogger(__name__)


def provider_conf(name: str, api_key: str, base_url: str, model: str) -> dict[str, str]:
    return {
        "provider": name,
        "api_key": api_key,
        "base_url": (base_url or "").rstrip("/"),
        "model": model,
    }


_PROVIDERS = {"qwen": "qwen", "dashscope": "qwen", "deepseek": "deepseek"}
_TENANT = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,15}")


def _normalized(value: str | None) -> str:
    return (value or "").strip().lower()


def model_family(model: str | None) -> str | None:
    """Interpret a known historical model key when no real provider was stored."""
    key = _normalized(model)
    if key == "deepseek" or key.startswith("deepseek-"):
        return "deepseek"
    if key.startswith(("qwen", "qwq", "dashscope-")):
        return "qwen"
    return None


def _model_name(settings, provider: str, model: str) -> str:
    if not model or (provider == "deepseek" and model.lower() in ("deepseek", "deepseek-chat")):
        return getattr(settings, provider + "_model")
    return model


async def call_chat(chosen: dict[str, str], payload: dict[str, Any], timeout: int,
                    *, scene: str = "对话", tenant_code: str | None = None,
                    meter_usage: bool = True, _transport=None) -> dict[str, Any]:
    """One bounded HTTP attempt. Optional legacy usage metering is best-effort.

    Runtime can retain provider usage in its atomic MODEL payload and settle it
    separately. It must opt out explicitly to avoid a second mutable ledger.
    Payload/provider-error logging is intentionally replaced by stable metadata.
    """
    trace = get_trace_id() or "-"
    started = time.perf_counter()
    try:
        data = await _send_chat_json(chosen,payload,timeout,transport=_transport)
    except ModelCallFailure as failure:
        logger.warning("模型调用失败 trace=%s code=%s status=%s",trace,failure.code,failure.status_code or "-")
        if meter_usage and failure.attempted:
            _meter(tenant_code=tenant_code,chosen=chosen,scene=scene,latency_ms=int((time.perf_counter()-started)*1000),success=False)
        raise
    latency_ms = int((time.perf_counter() - started) * 1000)
    if meter_usage:
        _meter(tenant_code=tenant_code or get_tenant_code() or None,chosen=chosen,scene=scene,latency_ms=latency_ms,data=data)
    logger.info("模型调用完成 trace=%s latencyMs=%d",trace,latency_ms)
    return data


def _meter(**values):
    try: record_chat_usage(**values)
    except Exception:
        logger.warning("MODEL_USAGE_UNAVAILABLE")


def message_content(data: dict[str, Any]) -> str:
    """从响应里取正文（兼容个别厂商把字段名写错的情况）。"""
    choices = data.get("choices") or []
    if not choices:
        return ""
    message = choices[0].get("message") or {}
    return str(message.get("content") or "").strip()


@dataclass(frozen=True)
class _ChatRoute:
    code: str
    tenant_provider: str = ""
    tenant_model: str = ""
    chosen: dict[str, str] | None = field(default=None, repr=False)


def _tenant_selection(tenant: str) -> dict | None:
    with connect(connect_timeout=2) as conn:
        conn.execute("SET LOCAL statement_timeout = '1500ms'")
        return conn.execute(
            """
            SELECT s.model_key, m.provider, m.is_enabled
              FROM bot_setting s
              LEFT JOIN bot_model m ON m.tenant_code = s.tenant_code
                                    AND m.model_key = s.model_key AND m.is_deleted = FALSE
             WHERE s.tenant_code = %s AND s.is_deleted = FALSE
            """,
            (tenant,),
        ).fetchone()


def _platform_selection(settings) -> tuple[str | None, str]:
    provider = _normalized(settings.llm_default_provider)
    model = settings.llm_default_model.strip()
    if provider in _PROVIDERS:
        return _PROVIDERS[provider], model
    if provider not in ("", "auto", "mock"):
        return None, model
    if model:
        return model_family(model), model
    if settings.qwen_api_key:
        return "qwen", ""
    if settings.deepseek_api_key:
        return "deepseek", ""
    return None, ""


def _resolve_route(tenant: str, provider: str | None, model: str | None) -> _ChatRoute:
    if not isinstance(tenant, str) or not _TENANT.fullmatch(tenant) or tenant.upper() == "PLATFORM":
        return _ChatRoute("MODEL_TENANT_REQUIRED")
    if _normalized(provider) == "mock":
        return _ChatRoute("MODEL_LOCAL_ONLY")
    try:
        selection = _tenant_selection(tenant)
    except Exception as exc:  # The configuration must be read successfully before using platform defaults.
        logger.warning("租户模型配置读取失败 trace=%s errorType=%s", get_trace_id() or "-", type(exc).__name__)
        return _ChatRoute("MODEL_TENANT_CONFIG_UNAVAILABLE")

    settings = get_settings()
    selected_model = ((selection or {}).get("model_key") or "").strip()
    selected_provider = ((selection or {}).get("provider") or "").strip()
    if selected_model:
        normalized_provider = _normalized(selected_provider)
        permitted = _PROVIDERS.get(normalized_provider)
        if normalized_provider in ("", "supportflow"):
            permitted = model_family(selected_model)
        if not permitted or selection["is_enabled"] is not True:
            return _ChatRoute("MODEL_SELECTION_INVALID", selected_provider, selected_model)
        requested_model = selected_model
    else:
        permitted, requested_model = _platform_selection(settings)
        if not permitted:
            return _ChatRoute("MODEL_PROVIDER_UNAVAILABLE")

    actual_model = _model_name(settings, permitted, requested_model)
    override_provider, override_model = _normalized(provider), (model or "").strip()
    if ((override_provider and _PROVIDERS.get(override_provider) != permitted)
            or (override_model and override_model not in (requested_model, actual_model))):
        return _ChatRoute("MODEL_ROUTE_OVERRIDE_DENIED", selected_provider, selected_model)

    key = getattr(settings, permitted + "_api_key")
    if not key.strip():
        return _ChatRoute("MODEL_PROVIDER_UNAVAILABLE", selected_provider, selected_model)
    chosen = provider_conf(permitted, key, getattr(settings, permitted + "_base_url"), actual_model)
    return _ChatRoute("MODEL_ROUTE_READY", selected_provider, selected_model, chosen)


def resolve_provider(tenant_code: str, provider: str | None = None,
                     model: str | None = None) -> tuple[dict[str, str] | None, str]:
    """Resolve one authorized provider for this call; request hints cannot grant a route."""
    route = _resolve_route(tenant_code, provider, model)
    if route.chosen is None:
        logger.info("模型路由降级 trace=%s code=%s", get_trace_id() or "-", route.code)
        return None, route.code
    return route.chosen, f"{route.chosen['provider']}:{route.chosen['model']}"


def describe_effective(tenant_code: str) -> dict[str, str]:
    """Report the effective route from one snapshot without returning credentials."""
    route = _resolve_route(tenant_code, None, None)
    if route.chosen is not None:
        effective = f"{route.chosen['provider']}:{route.chosen['model']}"
        hint = ""
    else:
        effective = "本地降级"
        hint = {
            "MODEL_TENANT_CONFIG_UNAVAILABLE": "租户模型配置暂时不可读，当前使用本地降级。",
            "MODEL_SELECTION_INVALID": "租户选择的模型已停用、不存在或供应商不受支持。",
            "MODEL_PROVIDER_UNAVAILABLE": "当前路由没有可用的供应商密钥，使用本地降级。",
        }.get(route.code, "缺少有效的业务租户。")
    return {
        "tenantModelKey": route.tenant_model or "（未配置）",
        "tenantProvider": route.tenant_provider or "（未配置）",
        "effective": effective,
        "routeCode": route.code,
        "hint": hint,
    }
