package io.supportflow.tenant.dto;

import java.time.LocalDateTime;

/**
 * 租户列表的一行（带配额与今日用量）。
 */
public record TenantListItemVO(String tenantCode, Integer status, Integer isolationMode,
                               String schemaName, Integer dbVersion,
                               LocalDateTime upgradedAt, LocalDateTime expireTime,
                               LocalDateTime createTime, String companyName, String industry,
                               Integer qpsLimit, Integer dailyRequestLimit, Boolean quotaEnabled,
                               Integer todayRequests, Integer todayRejected,
                               Long upgradeCount, LocalDateTime lastUpgradeAt,
                               Long todaySecurityEvents, String isolationModeText,
                               String statusText) {
}
