package io.supportflow.customer.dto.ticket;

/** 工单转派入参。 */
public record AssignBody(Long toUserId, String toUserName, String remark) {
}
