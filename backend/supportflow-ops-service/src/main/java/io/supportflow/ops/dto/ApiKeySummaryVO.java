package io.supportflow.ops.dto;

/**
 * 密钥概览：总数 / 启用数 / 累计调用。
 */
public record ApiKeySummaryVO(int total, int enabled, long calls) {
}
