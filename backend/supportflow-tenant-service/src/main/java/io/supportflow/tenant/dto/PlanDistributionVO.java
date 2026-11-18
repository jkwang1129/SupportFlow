package io.supportflow.tenant.dto;

/**
 * 按套餐的租户分布。
 */
public record PlanDistributionVO(String planId, int tenantCount) {
}
