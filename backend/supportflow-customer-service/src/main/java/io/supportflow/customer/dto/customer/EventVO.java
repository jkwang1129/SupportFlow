package io.supportflow.customer.dto.customer;

import java.time.LocalDateTime;

/** 客户动态的一行 */
public record EventVO(
        String id,
        Integer eventType,
        String eventTypeText,
        String title,
        String content,
        String operatorName,
        LocalDateTime eventTime) {
}
