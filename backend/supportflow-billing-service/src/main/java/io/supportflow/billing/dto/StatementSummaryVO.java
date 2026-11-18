package io.supportflow.billing.dto;

import java.math.BigDecimal;

/**
 * 账期账单汇总（老库还没建表时按全 0 返回，不让整页统计带崩）。
 */
public record StatementSummaryVO(Long statements, BigDecimal settledAmount,
                                 BigDecimal pendingAmount, BigDecimal overdueAmount,
                                 Long overdueCount) {
}
