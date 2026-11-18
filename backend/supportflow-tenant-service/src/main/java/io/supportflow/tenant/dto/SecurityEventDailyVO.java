package io.supportflow.tenant.dto;

/**
 * 安全事件近 7 天趋势的一点。
 */
public record SecurityEventDailyVO(String statDate, int events) {
}
