package io.supportflow.realtime.dto;

import java.util.Map;

/** 广播消息请求体 */
public record NotifyMessageBody(
        String tenantCode,
        String sessionNo,
        Map<String, Object> message,
        Boolean refreshAgents
) {
}
