package io.supportflow.billing.dto;

import java.math.BigDecimal;

/**
 * 收款流水的一行（对账用）。
 *
 * @param payTime 已格式化成 {@code yyyy-MM-dd HH:mm:ss}，前端直接显示
 */
public record PaymentRowVO(String id, String tenantCode, String orderId, Integer bizType,
                           String bizNo, String payNo, String payMethod, Integer payMethodCode,
                           BigDecimal amount, Integer status, String payTime, String remark,
                           String creator, String bizTypeText, String payMethodText,
                           String statusText) {
}
