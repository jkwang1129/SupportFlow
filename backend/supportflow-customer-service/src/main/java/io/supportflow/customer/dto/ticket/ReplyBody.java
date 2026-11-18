package io.supportflow.customer.dto.ticket;

/** 工单回复入参（visibleToCustomer 决定客户能不能看到）。 */
public record ReplyBody(String content, Boolean visibleToCustomer) {
}
