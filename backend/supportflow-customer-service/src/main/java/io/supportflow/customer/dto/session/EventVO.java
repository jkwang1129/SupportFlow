package io.supportflow.customer.dto.session;

import java.time.LocalDateTime;

/** 会话流转记录 */
public record EventVO(
        Integer eventType,
        Long operatorId,
        String fromValue,
        String toValue,
        String remark,
        LocalDateTime eventTime
) {
}
