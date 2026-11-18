package io.supportflow.billing.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 平台账单概览：本月账单 / 已收 / 待收 / 逾期 + 套餐分布。
 *
 * <p>{@code paidOrders} / {@code paidAmount} 来自**订单**（预付费买套餐），
 * 与账期账单是两个口径，分开摆才不会出现"付过钱却显示已收 0"。</p>
 */
public record BillingOverviewVO(String cycleKey, Long statements, BigDecimal totalAmount,
                                BigDecimal settledAmount, BigDecimal pendingAmount,
                                Long overdueCount, Long paidOrders, BigDecimal paidAmount,
                                List<PlanDistributionVO> planDistribution) {
}
