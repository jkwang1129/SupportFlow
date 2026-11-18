package io.supportflow.realtime.dto;

/** 输入状态请求体 */
public record TypingBody(String tenantCode, String sessionNo, String who, Boolean typing) {
}
