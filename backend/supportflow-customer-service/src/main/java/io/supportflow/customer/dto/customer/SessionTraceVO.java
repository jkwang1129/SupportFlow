package io.supportflow.customer.dto.customer;

import java.time.LocalDateTime;

/** 会话轨迹的一行 */
public record SessionTraceVO(
        String sessionNo,
        Integer status,
        String statusText,
        String channelName,
        String source,
        String agentId,
        String intent,
        String emotion,
        String botTransferReason,
        Integer msgCount,
        Integer csatScore,
        String ticketNo,
        Integer ticketStatus,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Integer durationMinutes) {
}
