package io.supportflow.realtime.dto;

import java.util.Map;

/** 实时质检预警请求体 */
public record QaAlertBody(String tenantCode, String sessionNo, Long agentId, Map<String, Object> payload) {
}
