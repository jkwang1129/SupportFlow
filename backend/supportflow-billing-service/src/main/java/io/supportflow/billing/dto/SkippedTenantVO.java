package io.supportflow.billing.dto;

/**
 * 批量出账时被跳过的租户与原因。
 */
public record SkippedTenantVO(String tenantCode, String reason) {
}
