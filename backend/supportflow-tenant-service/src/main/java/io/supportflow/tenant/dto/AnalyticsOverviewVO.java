package io.supportflow.tenant.dto;

/**
 * 平台数据页顶部卡片：租户规模 + 本月用量。
 */
public record AnalyticsOverviewVO(Integer tenantTotal, Integer tenantActive, Integer tenantFrozen,
                                  Integer tenantExpired, Integer tenantCancelled,
                                  Integer tenantIsolated, Integer paidTenants,
                                  Integer newThisMonth, Integer monthRequests,
                                  Integer monthRejected, Integer monthSessions,
                                  Integer monthAiCalls, String monthKey) {
}
