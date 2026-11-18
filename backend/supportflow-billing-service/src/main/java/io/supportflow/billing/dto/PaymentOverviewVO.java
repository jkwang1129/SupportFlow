package io.supportflow.billing.dto;

import java.math.BigDecimal;

/**
 * 收款概览：累计 / 本月 / 今日（只算成功收款）+ 订单收款与账单收款各自的笔数。
 */
public record PaymentOverviewVO(BigDecimal total, Integer totalCount, BigDecimal month,
                                BigDecimal today, Integer orderCount, Integer statementCount) {
}
