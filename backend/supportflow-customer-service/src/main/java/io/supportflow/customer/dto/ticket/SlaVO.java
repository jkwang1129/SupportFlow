package io.supportflow.customer.dto.ticket;

/** 工单当前的 SLA 时限（分钟）。 */
public record SlaVO(int firstResponseMinutes, int resolveMinutes) {
}
