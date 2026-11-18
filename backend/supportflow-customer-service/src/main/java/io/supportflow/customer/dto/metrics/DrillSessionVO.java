package io.supportflow.customer.dto.metrics;

import java.time.LocalDateTime;

/** 下钻明细：某个坐席某天的会话。 */
public record DrillSessionVO(String sessionNo, String customerName, String source, int status,
                             int msgCount, int csatScore, String intent, String emotion,
                             LocalDateTime startTime, LocalDateTime endTime, Integer durationMinutes) {
}
