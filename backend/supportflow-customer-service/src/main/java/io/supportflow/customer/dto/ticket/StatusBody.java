package io.supportflow.customer.dto.ticket;

/** 工单改状态入参。 */
public record StatusBody(Integer status, String remark) {
}
