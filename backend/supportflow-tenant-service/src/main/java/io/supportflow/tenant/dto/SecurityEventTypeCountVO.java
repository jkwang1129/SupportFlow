package io.supportflow.tenant.dto;

/**
 * 按事件类型的分布。
 */
public record SecurityEventTypeCountVO(int eventType, String eventTypeText, int events) {
}
