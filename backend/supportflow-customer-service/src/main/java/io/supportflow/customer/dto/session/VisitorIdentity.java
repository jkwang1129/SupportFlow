package io.supportflow.customer.dto.session;

/**
 * 访客身份（长期令牌解析结果）。
 */
public record VisitorIdentity(long customerId, String customerNo, String tenantCode, String customerName) {
}
