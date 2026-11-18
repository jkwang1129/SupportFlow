package io.supportflow.tenant.dto;

/**
 * 平台数据页的租户列表行。
 */
public record AnalyticsTenantRowVO(String tenantCode, String companyName, String industry,
                                   String planId, int status, String statusText,
                                   String expireTime, String createTime, int monthRequests,
                                   int monthSessions, String lastActiveDate) {
}
