package io.supportflow.billing.dto;

import java.math.BigDecimal;

/**
 * 按支付方式汇总的实收。
 */
public record PayMethodRevenueVO(int payMethod, String payMethodText, Long paidOrders,
                                 BigDecimal paidAmount) {
}
