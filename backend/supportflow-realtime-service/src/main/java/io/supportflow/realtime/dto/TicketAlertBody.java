package io.supportflow.realtime.dto;

import java.util.Map;

/** 工单提醒请求体 */
public record TicketAlertBody(String tenantCode, Long assigneeId, Map<String, Object> payload) {
}
