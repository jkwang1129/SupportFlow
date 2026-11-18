package io.supportflow.tenant.dto;

/**
 * 本月请求量 Top 租户。
 */
public record TopTenantVO(String tenantCode, String companyName, int status, String statusText,
                          String planId, int monthRequests, int monthSessions) {
}
