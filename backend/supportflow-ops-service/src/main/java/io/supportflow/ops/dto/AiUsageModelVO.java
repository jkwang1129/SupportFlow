package io.supportflow.ops.dto;

/**
 * AI 用量按模型的一项。
 */
public record AiUsageModelVO(String modelKey, Integer calls, Integer failedCalls, Integer tokens,
                             Double cost, Integer avgLatencyMs) {
}
