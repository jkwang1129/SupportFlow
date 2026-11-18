package io.supportflow.ops.dto;

/**
 * AI 用量按天的一项。
 */
public record AiUsageDailyVO(String statDate, Integer calls, Integer failedCalls, Integer tokens,
                             Double cost, Integer avgLatencyMs) {
}
