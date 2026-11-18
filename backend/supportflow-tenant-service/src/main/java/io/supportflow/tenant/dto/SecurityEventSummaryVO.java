package io.supportflow.tenant.dto;

/**
 * 安全事件概览：总数 / 待处置 / 今日 / 近 7 天。
 */
public record SecurityEventSummaryVO(int total, int pending, int today, int last7) {
}
