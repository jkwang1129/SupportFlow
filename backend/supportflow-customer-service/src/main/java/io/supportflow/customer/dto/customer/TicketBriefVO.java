package io.supportflow.customer.dto.customer;

import java.time.LocalDateTime;

/** 关联工单（画像里的"之前提过什么"） */
public record TicketBriefVO(
        String ticketNo,
        String title,
        String statusText,
        Integer priority,
        String priorityText,
        String assigneeName,
        String sessionNo,
        LocalDateTime createTime) {
}
