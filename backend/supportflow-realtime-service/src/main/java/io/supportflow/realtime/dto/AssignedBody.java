package io.supportflow.realtime.dto;

/** 自动接入通知请求体 */
public record AssignedBody(String tenantCode, long agentId, String sessionNo, String reason) {
}
