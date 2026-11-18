package io.supportflow.tenant.dto;

/**
 * 安全事件的一行（平台安全看板）。
 */
public record SecurityEventVO(String id, String tenantCode, String companyName, int eventType,
                              String eventTypeText, String detail, String requestUri,
                              String requestMethod, String clientIp, String tokenTenant,
                              String operatorName, String eventTime, int handleStatus,
                              String handleStatusText, String handleRemark, String handlerName,
                              String handleTime) {
}
