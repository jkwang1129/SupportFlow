package io.supportflow.tenant.dto;

import java.time.LocalDateTime;

/**
 * 租户隔离安全事件（越界访问 / 被限流这类）。
 */
public record TenantSecurityEventVO(Long id, String tenantCode, Integer eventType, String detail,
                                    String requestUri, String requestMethod, String clientIp,
                                    String tokenTenant, String operatorName,
                                    LocalDateTime eventTime) {
}
