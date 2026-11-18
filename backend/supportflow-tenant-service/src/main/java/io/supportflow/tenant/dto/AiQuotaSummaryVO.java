package io.supportflow.tenant.dto;

/**
 * AI 配额大盘概览。
 */
public record AiQuotaSummaryVO(String statDate, int tenants, int todayCalls, int warningTenants,
                               int exceededTenants, int unlimitedTenants, boolean usageAvailable) {
}
