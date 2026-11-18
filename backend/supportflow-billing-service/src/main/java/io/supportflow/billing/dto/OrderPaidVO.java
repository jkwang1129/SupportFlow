package io.supportflow.billing.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付成功的回执。
 *
 * @param tenantPlanSynced 套餐是否已同步到 tenant-service（false 时要重试，否则"付了钱没开通"）
 */
public record OrderPaidVO(String orderNo, int status, String statusText, LocalDateTime payTime,
                          Long planId, String planName, BigDecimal amount, boolean tenantPlanSynced) {
}
