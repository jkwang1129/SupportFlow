package io.supportflow.tenant.dto;

/** 网关上报告警的一条 */
public record SecurityReport(String tenantCode, Integer eventType, String detail,
                             String requestUri, String requestMethod, String clientIp,
                             String tokenTenant, String operatorName) {
}
