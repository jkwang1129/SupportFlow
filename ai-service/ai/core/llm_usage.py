"""AI 调用计量：每次真实调模型落一条 ``ai_usage_record``。

为什么埋在这一层（而不是各个业务里）：全平台的模型调用都走
``core.llm_chat.call_chat`` 与 ``core.llm_vision.vision_chat`` 两个入口，
在这两处各记一次，质检 / 问答 / 客服大脑 / 视觉识别一个都漏不掉；
写在业务里迟早出现"新加的场景忘了记"。

三条硬约定：
  1. **绝不打扰主流程**：写计量失败只告警，模型调用该返回什么还返回什么；
  2. **token 以厂商返回的 usage 为准**，拿不到就 0（不猜、不按字数估）；
  3. **成本按挂牌价估算**并写明来源，方便运营据此对账（真要精确对接账单再换算法）。
"""

from __future__ import annotations

import logging
from datetime import date
from typing import Any

from .db import connect
from .tenant import get_tenant_code
from .trace import get_trace_id

logger = logging.getLogger(__name__)

# 场景码（与 ai_usage_record.ck_ai_usage_record_scene 对齐）
SCENE_CHAT = 1
SCENE_QA = 2
SCENE_VISION = 3

SCENE_TEXT = {
    SCENE_CHAT: "对话",
    SCENE_QA: "质检",
    SCENE_VISION: "视觉识别",
}

# 调用方传的场景名 → 场景码。对话 / 问答 / 客服大脑都算"对话"这一类，
# 质检与视觉单独成类——平台看成本时最想知道的就是"钱花在哪类调用上"。
SCENE_CODES = {
    "对话": SCENE_CHAT,
    "客服大脑": SCENE_CHAT,
    "知识问答": SCENE_CHAT,
    "质检": SCENE_QA,
    "AI质检": SCENE_QA,
    "视觉识别": SCENE_VISION,
    "图片识别": SCENE_VISION,
}

# 价格表：元 / 千 token，(输入价, 输出价)。
# 来源是各家公开挂牌价（不含缓存命中折扣），运营要按合同价对账就改这里。
PRICES: dict[str, tuple[float, float]] = {
    "qwen-plus": (0.0008, 0.002),
    "qwen-max": (0.0024, 0.0096),
    "qwen-vl-plus": (0.0015, 0.0045),
    "qwen-vl-max": (0.003, 0.009),
    "deepseek-chat": (0.001, 0.002),
    "deepseek-reasoner": (0.004, 0.016),
}

# 认不出来的模型按"中档"估：宁可略高，也别让新模型的成本显示成 0
DEFAULT_PRICE = (0.001, 0.002)

# 历史遗留的降级标记：早期版本会把"走了规则引擎、没真调模型"的一次请求记成 model_key='mock'。
# 现在不再写这种行（AI 监控只统计真实模型调用），但聚合侧仍然把它排除掉，
# 免得老数据混进调用量与成本。
DEGRADED_MODEL = "mock"


def scene_code(scene: str | None) -> int:
    return SCENE_CODES.get((scene or "").strip(), SCENE_CHAT)


def price_of(model_key: str | None) -> tuple[float, float]:
    """按模型名找价格；带版本后缀的（如 qwen-plus-2025-xx）按前缀匹配。"""
    name = (model_key or "").strip().lower()
    if name in PRICES:
        return PRICES[name]
    for key, value in PRICES.items():
        if name.startswith(key):
            return value
    return DEFAULT_PRICE


def estimate_cost(model_key: str | None, input_tokens: int, output_tokens: int) -> float:
    input_price, output_price = price_of(model_key)
    cost = (input_tokens / 1000) * input_price + (output_tokens / 1000) * output_price
    return round(cost, 4)


def usage_tokens(data: dict[str, Any] | None) -> tuple[int, int]:
    """从厂商响应里取 token：不同兼容网关字段名略有差异，两种都认。"""
    usage = (data or {}).get("usage") or {}
    prompt = usage.get("prompt_tokens", usage.get("input_tokens", 0))
    completion = usage.get("completion_tokens", usage.get("output_tokens", 0))
    try:
        return int(prompt or 0), int(completion or 0)
    except (TypeError, ValueError):
        return 0, 0


def record_usage(*, tenant_code: str | None, model_key: str | None, scene: str | None,
                 data: dict[str, Any] | None = None, input_tokens: int | None = None,
                 output_tokens: int | None = None, latency_ms: int = 0,
                 success: bool = True) -> None:
    """落一条计量。**任何异常都只告警**：计量失败不许影响模型调用本身。"""
    try:
        if input_tokens is None or output_tokens is None:
            counted_in, counted_out = usage_tokens(data)
            input_tokens = counted_in if input_tokens is None else input_tokens
            output_tokens = counted_out if output_tokens is None else output_tokens
        tenant = tenant_code or get_tenant_code() or "UNKNOWN"
        code = scene_code(scene)
        cost = estimate_cost(model_key, input_tokens, output_tokens)
        from ..rag.store import next_id  # 延迟导入：避免 core 与 rag 循环依赖

        with connect() as conn:
            conn.execute(
                """
                INSERT INTO ai_usage_record
                    (id, tenant_code, model_key, scene, input_tokens, output_tokens,
                     cost_amount, latency_ms, is_success, request_id, stat_date)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                """,
                (next_id(), tenant, (model_key or "unknown")[:64], code,
                 int(input_tokens), int(output_tokens), cost, int(latency_ms),
                 bool(success), (get_trace_id() or None), date.today()),
            )
            conn.commit()
        logger.info("AI 计量已记录 tenant=%s model=%s scene=%s in=%d out=%d cost=%.4f latencyMs=%d success=%s",
                    tenant, model_key, SCENE_TEXT.get(code, code), input_tokens, output_tokens,
                    cost, latency_ms, success)
    except Exception as exc:  # noqa: BLE001
        logger.warning("AI 计量写入失败 errorType=%s", type(exc).__name__)


def record_chat_usage(*, tenant_code: str | None, chosen: dict[str, str] | None,
                      scene: str | None, latency_ms: int, data: dict[str, Any] | None = None,
                      success: bool = True) -> None:
    """对话类调用的便捷入口（provider / model 从 chosen 里取）。"""
    model = (chosen or {}).get("model") or (data or {}).get("model")
    record_usage(tenant_code=tenant_code, model_key=model, scene=scene, data=data,
                 latency_ms=latency_ms, success=success)
