package io.supportflow.ops.dto;

/**
 * AI 用量总览（近 N 天，只统计真实模型调用）。
 */
public record AiUsageOverviewVO(Integer calls, Integer failedCalls, Double successRate,
                                Integer inputTokens, Integer outputTokens, Integer tokens,
                                Double cost, Integer avgLatencyMs, Integer tenants) {
}
