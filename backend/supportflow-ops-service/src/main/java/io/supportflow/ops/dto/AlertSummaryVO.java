package io.supportflow.ops.dto;

/**
 * 告警概览：总数 / 待处理 / 严重 / 警告 / 今日。
 */
public record AlertSummaryVO(int total, int pending, int critical, int warning, int today) {
}
