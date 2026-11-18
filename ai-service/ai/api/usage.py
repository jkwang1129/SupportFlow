"""AI 调用计量查询（平台「AI 监控」用）。

数据来自 ``ai_usage_record``：每次真实调模型落一条（模型 / 场景 / token / 成本 / 耗时 / 成败）。
这里只出**聚合数字**，不出对话内容——平台看的是成本与稳定性，不是企业聊了什么。

两个用途分开成两个接口：
  * ``/internal/usage/metrics``：平台监控大盘（调用量、token、成本、按天/模型/场景/租户）；
  * ``/internal/usage/tenants``：按租户的今日用量，给「租户配额」用（tenant-service 来拉）。
"""

from __future__ import annotations

from datetime import date, timedelta

from fastapi import APIRouter, Query

from ..core.db import connect
from ..config import get_settings
from ..core.llm_usage import DEGRADED_MODEL, SCENE_TEXT

router = APIRouter(tags=["usage"])

# 单次查询最多往回看多少天，防止有人传个 99999 把库拖死
MAX_DAYS = 90


@router.get("/internal/usage/metrics")
def metrics(days: int = Query(default=7, ge=1, le=MAX_DAYS)) -> dict:
    """近 N 天 AI 用量大盘：总览 + 按天 + 按模型 + 按场景 + 成本 Top 租户。

    **只统计真实模型调用**：走了规则/检索（没配密钥、模型不可用）的请求不产生计量，
    所以这里的数字就是"AI 真实花的钱与调用量"，不掺任何兜底数据。
    （历史版本写过 ``model_key='mock'`` 的降级行，聚合时一并排除，避免老数据混进来。）
    """
    span = max(1, min(days, MAX_DAYS))
    start = date.today() - timedelta(days=span - 1)
    with connect() as conn:
        overview = conn.execute(
            """
            SELECT COUNT(1)                                                        AS calls,
                   COUNT(1) FILTER (WHERE is_success = FALSE)                      AS failed_calls,
                   COALESCE(SUM(input_tokens), 0)                                  AS input_tokens,
                   COALESCE(SUM(output_tokens), 0)                                 AS output_tokens,
                   COALESCE(SUM(cost_amount), 0)                                   AS cost,
                   COALESCE(ROUND(AVG(latency_ms))::INT, 0)                        AS avg_latency_ms,
                   COUNT(DISTINCT tenant_code)                                     AS tenants
              FROM ai_usage_record
             WHERE stat_date >= %s AND model_key <> %s
            """,
            (start, DEGRADED_MODEL),
        ).fetchone()
        daily = conn.execute(
            """
            SELECT to_char(stat_date, 'YYYY-MM-DD')                     AS stat_date,
                   COUNT(1)                                            AS calls,
                   COUNT(1) FILTER (WHERE is_success = FALSE)           AS failed_calls,
                   COALESCE(SUM(input_tokens + output_tokens), 0)       AS tokens,
                   COALESCE(SUM(cost_amount), 0)                        AS cost,
                   COALESCE(ROUND(AVG(latency_ms))::INT, 0)             AS avg_latency_ms
              FROM ai_usage_record
             WHERE stat_date >= %s AND model_key <> %s
             GROUP BY stat_date
             ORDER BY stat_date
            """,
            (start, DEGRADED_MODEL),
        ).fetchall()
        by_model = conn.execute(
            """
            SELECT model_key                                            AS model_key,
                   COUNT(1)                                            AS calls,
                   COALESCE(SUM(input_tokens + output_tokens), 0)       AS tokens,
                   COALESCE(SUM(cost_amount), 0)                        AS cost,
                   COALESCE(ROUND(AVG(latency_ms))::INT, 0)             AS avg_latency_ms,
                   COUNT(1) FILTER (WHERE is_success = FALSE)           AS failed_calls
              FROM ai_usage_record
             WHERE stat_date >= %s AND model_key <> %s
             GROUP BY model_key
             ORDER BY cost DESC, calls DESC
            """,
            (start, DEGRADED_MODEL),
        ).fetchall()
        by_scene = conn.execute(
            """
            SELECT scene                                                AS scene,
                   COUNT(1)                                            AS calls,
                   COALESCE(SUM(input_tokens + output_tokens), 0)       AS tokens,
                   COALESCE(SUM(cost_amount), 0)                        AS cost
              FROM ai_usage_record
             WHERE stat_date >= %s AND model_key <> %s
             GROUP BY scene
             ORDER BY scene
            """,
            (start, DEGRADED_MODEL),
        ).fetchall()
        top_tenants = conn.execute(
            """
            SELECT tenant_code                                          AS tenant_code,
                   COUNT(1)                                            AS calls,
                   COALESCE(SUM(input_tokens + output_tokens), 0)       AS tokens,
                   COALESCE(SUM(cost_amount), 0)                        AS cost
              FROM ai_usage_record
             WHERE stat_date >= %s AND model_key <> %s
             GROUP BY tenant_code
             ORDER BY cost DESC, calls DESC
             LIMIT 8
            """,
            (start, DEGRADED_MODEL),
        ).fetchall()
        last_real = conn.execute(
            "SELECT MAX(create_time) AS last_at FROM ai_usage_record "
            "WHERE model_key <> %s",
            (DEGRADED_MODEL,),
        ).fetchone()

    calls = int(overview["calls"] or 0)
    failed = int(overview["failed_calls"] or 0)
    return {
        "days": span,
        "from": start.isoformat(),
        "to": date.today().isoformat(),
        "overview": {
            "calls": calls,
            "failedCalls": failed,
            "successRate": round((calls - failed) / calls * 100, 2) if calls else 100.0,
            "inputTokens": int(overview["input_tokens"] or 0),
            "outputTokens": int(overview["output_tokens"] or 0),
            "tokens": int(overview["input_tokens"] or 0) + int(overview["output_tokens"] or 0),
            "cost": float(overview["cost"] or 0),
            "avgLatencyMs": int(overview["avg_latency_ms"] or 0),
            "tenants": int(overview["tenants"] or 0),
        },
        "daily": [
            {
                "statDate": row["stat_date"],
                "calls": int(row["calls"] or 0),
                "failedCalls": int(row["failed_calls"] or 0),
                "tokens": int(row["tokens"] or 0),
                "cost": float(row["cost"] or 0),
                "avgLatencyMs": int(row["avg_latency_ms"] or 0),
            }
            for row in daily
        ],
        "byModel": [
            {
                "modelKey": row["model_key"],
                "calls": int(row["calls"] or 0),
                "failedCalls": int(row["failed_calls"] or 0),
                "tokens": int(row["tokens"] or 0),
                "cost": float(row["cost"] or 0),
                "avgLatencyMs": int(row["avg_latency_ms"] or 0),
            }
            for row in by_model
        ],
        "byScene": [
            {
                "scene": int(row["scene"] or 1),
                "sceneText": SCENE_TEXT.get(int(row["scene"] or 1), "其他"),
                "calls": int(row["calls"] or 0),
                "tokens": int(row["tokens"] or 0),
                "cost": float(row["cost"] or 0),
            }
            for row in by_scene
        ],
        "topTenants": [
            {
                "tenantCode": row["tenant_code"],
                "calls": int(row["calls"] or 0),
                "tokens": int(row["tokens"] or 0),
                "cost": float(row["cost"] or 0),
            }
            for row in top_tenants
        ],
        "lastRealCallAt": str(last_real["last_at"])[:19] if last_real and last_real["last_at"] else None,
    }


@router.get("/internal/usage/status")
def status() -> dict:
    """计量状态：AI 服务当前是否在用真实模型（页面顶部状态条用）。

    ``llmEnabled=false`` 表示没配任何厂商密钥：这时请求会走规则 / 检索原文，
    不会产生任何调用量与成本——页面据此给出准确解释，而不是让人对着一个 0 猜。
    """
    settings = get_settings()
    providers = {
        "qwen": bool(settings.qwen_api_key),
        "deepseek": bool(settings.deepseek_api_key),
    }
    enabled = [name for name, configured in providers.items() if configured]
    with connect() as conn:
        last_real = conn.execute(
            "SELECT MAX(create_time) AS last_at FROM ai_usage_record WHERE model_key <> %s",
            (DEGRADED_MODEL,),
        ).fetchone()
        today = conn.execute(
            """
            SELECT COUNT(1) AS real_calls
              FROM ai_usage_record
             WHERE stat_date = CURRENT_DATE AND model_key <> %s
            """,
            (DEGRADED_MODEL,),
        ).fetchone()
    return {
        "llmEnabled": bool(enabled),
        "providers": providers,
        "enabledProviders": enabled,
        "defaultProvider": settings.llm_default_provider,
        "qwenModel": settings.qwen_model,
        "deepseekModel": settings.deepseek_model,
        "lastRealCallAt": str(last_real["last_at"])[:19] if last_real and last_real["last_at"] else None,
        "todayRealCalls": int(today["real_calls"] or 0),
    }


@router.get("/internal/usage/tenants")
def tenant_usage(stat_date: str | None = None) -> dict:
    """某一天各租户的 AI 用量（默认今天）。tenant-service 拿它算租户配额使用率。"""
    target = date.fromisoformat(stat_date) if stat_date else date.today()
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT tenant_code                                          AS tenant_code,
                   COUNT(1)                                            AS calls,
                   COUNT(1) FILTER (WHERE is_success = FALSE)           AS failed_calls,
                   COALESCE(SUM(input_tokens + output_tokens), 0)       AS tokens,
                   COALESCE(SUM(cost_amount), 0)                        AS cost
              FROM ai_usage_record
             WHERE stat_date = %s
             GROUP BY tenant_code
             ORDER BY calls DESC
            """,
            (target,),
        ).fetchall()
    return {
        "statDate": target.isoformat(),
        "items": [
            {
                "tenantCode": row["tenant_code"],
                "calls": int(row["calls"] or 0),
                "failedCalls": int(row["failed_calls"] or 0),
                "tokens": int(row["tokens"] or 0),
                "cost": float(row["cost"] or 0),
            }
            for row in rows
        ],
    }
