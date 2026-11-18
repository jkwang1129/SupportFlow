package io.supportflow.customer.dto.open;

/**
 * 开放接口的租户运营概览（只读聚合，供第三方按天拉一次对账）。
 */
public record OpenApiOverviewVO(String tenantCode, Long sessions, Long tickets, Long customers,
                                String generatedAt) {
}
