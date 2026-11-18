package io.supportflow.tenant.dto;

/**
 * 单个租户的 AI 配额用量。
 */
public record AiQuotaItemVO(String tenantCode, String companyName, int aiDailyLimit,
                            int todayCalls, int todayTokens, double todayCost, int failedCalls,
                            int usagePercent, int status, String statusText, int tenantStatus,
                            int dailyRequestLimit, boolean quotaEnabled) {
}
