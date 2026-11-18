package io.supportflow.ops.dto;

/**
 * 成本 Top 租户的一项。
 */
public record AiUsageTenantVO(String tenantCode, Integer calls, Integer tokens, Double cost) {
}
