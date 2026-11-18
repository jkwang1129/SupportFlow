package io.supportflow.realtime.dto;

/** 会话已被接入的同步请求体 */
public record ClaimedBody(String tenantCode, String sessionNo, Long agentId) {
}
