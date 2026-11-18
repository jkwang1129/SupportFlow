package io.supportflow.tenant.dto;

/**
 * 客户成功概览：健康 / 观察 / 风险分布 + 续费风险 + 商机 + 到期情况 + 平均分。
 */
public record TenantHealthSummaryVO(int tenants, int healthy, int watch, int risky, int highRisk,
                                    int midRisk, int opportunities, int expiringIn30Days,
                                    int expired, Long avgHealthScore) {
}
