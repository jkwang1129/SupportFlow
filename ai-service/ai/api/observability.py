"""可观测查询（平台「可观测」页用）：调用链明细 + 单租户成本。

数据全部来自 ``ai_usage_record``（AI 计量表），只做两件事：

* ``/internal/usage/trace/{trace_id}``：按调用链号查这一次请求在 AI 侧发生了什么
  （模型 / 场景 / token / 成本 / 耗时 / 成败 / 租户）。号来自 ``X-Request-Id``，
  前端 → 网关 → Java 服务 → AI 服务一路透传，所以页面报错时把号复制过来就能查到；
* ``/internal/usage/tenant-total``：单个租户近 N 天的成本与调用量，
  给「成本预算」算使用率用（预算与超限动作在 ops_db，用量只有这里知道）。

两个接口都只出**计量与状态**，不出对话内容——排查问题不需要看企业聊了什么。
"""

from __future__ import annotations

from datetime import date, timedelta

from fastapi import APIRouter, Query

from ..core.db import connect
from ..core.llm_usage import DEGRADED_MODEL, SCENE_TEXT

router = APIRouter(tags=["observability"])

# 单次查询最多往回看多少天（预算按月算，90 天足够）
MAX_DAYS = 90
# 一条调用链最多回多少行（重试多的链路也就几条）
TRACE_LIMIT = 20


@router.get("/internal/usage/trace/{trace_id}")
def trace(trace_id: str) -> dict:
    """按调用链号查 AI 侧的调用明细（不含对话内容，只出计量与状态）。"""
    key = (trace_id or "").strip()
    if not key:
        return {"found": False, "items": []}
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT id, tenant_code, model_key, scene, input_tokens, output_tokens,
                   cost_amount, latency_ms, is_success, request_id, remark,
                   to_char(create_time, 'YYYY-MM-DD HH24:MI:SS') AS create_time
              FROM ai_usage_record
             WHERE request_id = %s
             ORDER BY create_time ASC, id ASC
             LIMIT %s
            """,
            (key, TRACE_LIMIT),
        ).fetchall()
    items = []
    for row in rows:
        items.append({
            "id": str(row["id"]),
            "tenantCode": row["tenant_code"],
            "modelKey": row["model_key"],
            "scene": row["scene"],
            "sceneText": SCENE_TEXT.get(row["scene"], "未知场景"),
            "inputTokens": row["input_tokens"],
            "outputTokens": row["output_tokens"],
            "cost": float(row["cost_amount"] or 0),
            "latencyMs": row["latency_ms"],
            "success": bool(row["is_success"]),
            "requestId": row["request_id"],
            "remark": row["remark"],
            "time": row["create_time"],
        })
    return {"found": bool(items), "items": items}


@router.get("/internal/usage/tenant-total")
def tenant_total(
    tenant_code: str = Query(..., alias="tenantCode"),
    days: int = Query(default=30, ge=1, le=MAX_DAYS),
) -> dict:
    """单个租户近 N 天的成本 / 调用量 / token（成本预算算使用率用）。"""
    tenant = (tenant_code or "").strip()
    if not tenant:
        return {"tenantCode": "", "days": days, "cost": 0, "calls": 0, "tokens": 0}
    span = max(1, min(days, MAX_DAYS))
    start = date.today() - timedelta(days=span - 1)
    with connect() as conn:
        row = conn.execute(
            """
            SELECT COUNT(1)                                         AS calls,
                   COUNT(1) FILTER (WHERE is_success = FALSE)       AS failed_calls,
                   COALESCE(SUM(input_tokens + output_tokens), 0)   AS tokens,
                   COALESCE(SUM(cost_amount), 0)                    AS cost,
                   COALESCE(ROUND(AVG(latency_ms))::INT, 0)         AS avg_latency_ms
              FROM ai_usage_record
             WHERE tenant_code = %s AND stat_date >= %s AND model_key <> %s
            """,
            (tenant, start, DEGRADED_MODEL),
        ).fetchone()
    return {
        "tenantCode": tenant,
        "days": span,
        "startDate": start.isoformat(),
        "calls": row["calls"],
        "failedCalls": row["failed_calls"],
        "tokens": int(row["tokens"] or 0),
        "cost": float(row["cost"] or 0),
        "avgLatencyMs": row["avg_latency_ms"],
    }


@router.get("/internal/usage/tenant-metrics")
def tenant_metrics(days: int = Query(default=1, ge=1, le=MAX_DAYS)) -> dict:
    """近 N 天**按租户**的指标（一次查完）。

    告警评估器按租户判断时要"每个租户一行"：逐个租户调 tenant-total 是 N 次查询，
    租户一多就把 AI 服务打满；这里一条 GROUP BY 出全量，评估器只做比较。
    """
    span = max(1, min(days, MAX_DAYS))
    start = date.today() - timedelta(days=span - 1)
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT tenant_code                                        AS tenant_code,
                   COUNT(1)                                           AS calls,
                   COUNT(1) FILTER (WHERE is_success = FALSE)         AS failed_calls,
                   COALESCE(SUM(input_tokens + output_tokens), 0)     AS tokens,
                   COALESCE(SUM(cost_amount), 0)                      AS cost,
                   COALESCE(ROUND(AVG(latency_ms))::INT, 0)           AS avg_latency_ms
              FROM ai_usage_record
             WHERE stat_date >= %s AND model_key <> %s
             GROUP BY tenant_code
             ORDER BY cost DESC, calls DESC
            """,
            (start, DEGRADED_MODEL),
        ).fetchall()
    items = []
    for row in rows:
        items.append({
            "tenantCode": row["tenant_code"],
            "calls": row["calls"],
            "failedCalls": row["failed_calls"],
            "tokens": int(row["tokens"] or 0),
            "cost": float(row["cost"] or 0),
            "avgLatencyMs": row["avg_latency_ms"],
        })
    return {"days": span, "startDate": start.isoformat(), "items": items}
