package io.supportflow.billing.dto;

import java.math.BigDecimal;

/**
 * 按支付方式汇总的收款额。
 */
public record PayMethodSumVO(Integer payMethodCode, String payMethod, Long count,
                             BigDecimal amount) {
}
