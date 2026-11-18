package io.supportflow.billing.dto;

import java.math.BigDecimal;

/**
 * 实收趋势的一个月（空月份补 0，避免折线上出现断点被误读成"数据丢了"）。
 */
public record RevenueTrendVO(String month, BigDecimal paidAmount, Long paidOrders) {
}
