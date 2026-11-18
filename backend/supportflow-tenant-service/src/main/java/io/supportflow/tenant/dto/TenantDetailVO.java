package io.supportflow.tenant.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 租户详情：隔离与限流 + 版本状态 + 升级历史 + 安全事件。
 *
 * @param dataPlaneReachable 数据面是否连得上；false 时 version 字段是控制面的兜底值
 * @param pending            待执行的脚本清单（数据面给什么原样带回）
 */
public record TenantDetailVO(String tenantCode, Integer status, Integer isolationMode,
                             String schemaName, Integer dbVersion, LocalDateTime upgradedAt,
                             LocalDateTime expireTime, LocalDateTime createTime,
                             String companyName, String industry, String licenseNo,
                             String legalPerson, String contactName, Integer qpsLimit,
                             Integer dailyRequestLimit, Integer dailySessionLimit,
                             Integer aiDailyLimit, Boolean quotaEnabled, String quotaRemark,
                             Integer todayRequests, Integer todayRejected,
                             Integer todaySessions, Integer todayAiCalls,
                             Integer currentVersion, Integer latestVersion, List<?> pending,
                             Boolean dataPlaneReachable, List<UpgradeHistoryVO> upgrades,
                             List<TenantSecurityEventVO> securityEvents) {
}
