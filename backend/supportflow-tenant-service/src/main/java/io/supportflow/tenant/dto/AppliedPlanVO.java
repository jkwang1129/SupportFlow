package io.supportflow.tenant.dto;

import java.time.LocalDateTime;

/**
 * 套餐生效（billing 开通 / 续费后回调）后的回执：套餐 + 到期时间 + 同步过去的 QPS。
 */
public record AppliedPlanVO(String tenantCode, Long planId, String planName,
                            LocalDateTime expireTime, Integer qpsLimit) {
}
