package io.supportflow.customer.dto.ticket;

/** 工单流转记录的一条（谁在什么时候把它改成了什么）。 */
public record EventVO(
        String id,
        Integer eventType,
        String eventTypeText,
        String operatorName,
        String content,
        String fromStatusText,
        String toStatusText,
        boolean visibleToCustomer,
        String eventTime
) {
}
