package io.supportflow.billing.dto;

import java.math.BigDecimal;

/**
 * 按套餐汇总的实收。
 */
public record PlanRevenueVO(String planId, String planCode, String planName, Long paidOrders,
                            BigDecimal paidAmount) {
}
