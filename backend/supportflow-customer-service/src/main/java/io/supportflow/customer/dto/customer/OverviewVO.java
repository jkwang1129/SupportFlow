package io.supportflow.customer.dto.customer;

/** 客户经营概览 */
public record OverviewVO(int total, int monthNew, int active7d, int riskCount,
                         int vipCount, int taggedCount) {
}
