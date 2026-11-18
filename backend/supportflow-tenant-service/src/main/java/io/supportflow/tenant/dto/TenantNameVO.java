package io.supportflow.tenant.dto;

/**
 * 租户目录项：编码 → 企业名称（平台侧跨租户页面做映射用）。
 */
public record TenantNameVO(String tenantCode, String companyName) {
}
