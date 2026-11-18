package io.supportflow.customer.dto.session;

/**
 * 访客身份。
 */
public record VisitorPrincipal(long customerId, String customerName, String tenantCode, String sessionNo) {
}
