package io.supportflow.tenant.dto;

/**
 * 平台租户概览：总数 / 活跃 / 独立库 / 冻结 / 落后版本 / 今日被限流。
 */
public record TenantOverviewVO(Integer total, Integer active, Integer dedicated, Integer frozen,
                               Integer versionBehind, Long todayRejected, Integer latestVersion) {
}
