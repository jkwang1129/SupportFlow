package io.supportflow.tenant.dto;

import java.time.LocalDateTime;

/**
 * 某租户当前套餐（billing 算用量与账单时的权威来源在 tenant_db）。
 */
public record TenantPlanVO(String tenantCode, Long planId, LocalDateTime expireTime,
                           Integer status, Integer isolationMode) {
}
