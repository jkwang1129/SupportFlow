package io.supportflow.tenant.dto;

/**
 * 健康度四项得分（页面点开某行看"为什么是这个分"）。
 */
public record HealthScorePartsVO(int usage, int active, int expire, int trend) {
}
