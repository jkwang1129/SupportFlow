package io.supportflow.tenant.dto;

/**
 * 租户增长趋势的一个月（空月份补 0，折线不会出现断点）。
 *
 * @param cumulative 截至当月的累计租户数
 */
public record GrowthPointVO(String month, int newTenants, int cumulative) {
}
