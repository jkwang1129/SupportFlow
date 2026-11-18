package io.supportflow.customer.dto.session;

import java.time.LocalDateTime;

/**
 * 会话信息
 */
public record SessionInfo(
        String sessionNo,
        String tenantCode,
        Long sessionId,
        Integer status,
        Long agentId,
        Long customerId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        /** 会话当前最大消息序号：客户端据此判断要不要补拉 */
        Long lastSeq
) {
}
