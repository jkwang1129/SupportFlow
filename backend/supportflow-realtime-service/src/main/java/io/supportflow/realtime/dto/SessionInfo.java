package io.supportflow.realtime.dto;

/** 会话信息 */
public record SessionInfo(
        String sessionNo,
        String tenantCode,
        Long sessionId,
        Integer status,
        Long agentId,
        Long customerId,
        String startTime,
        String endTime,
        /** 会话当前最大消息序号：用来告诉客户端要不要补拉 */
        Long lastSeq
) {
    public boolean closed() {
        return status != null && status == 4;
    }
}
