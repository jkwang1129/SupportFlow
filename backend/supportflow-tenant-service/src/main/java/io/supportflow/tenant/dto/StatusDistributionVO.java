package io.supportflow.tenant.dto;

/**
 * 按状态的租户分布（1-正常、2-冻结、3-到期、4-注销）。
 */
public record StatusDistributionVO(int status, String statusText, int tenantCount) {
}
