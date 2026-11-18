package io.supportflow.billing.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 下单成功后的订单摘要（状态=待支付）。
 */
public record OrderCreatedVO(String orderNo, String tenantCode, Long planId, String planName,
                             int cycle, BigDecimal amount, int status, String statusText,
                             LocalDateTime createTime) {
}
