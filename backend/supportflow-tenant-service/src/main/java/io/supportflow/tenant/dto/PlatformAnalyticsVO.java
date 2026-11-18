package io.supportflow.tenant.dto;

import java.util.List;

/**
 * 平台经营分析：一次把页面要的聚合全给了（省得前端串 5 个请求）。
 */
public record PlatformAnalyticsVO(AnalyticsOverviewVO overview, List<GrowthPointVO> growth,
                                  List<PlanDistributionVO> planDistribution,
                                  List<StatusDistributionVO> statusDistribution,
                                  List<TopTenantVO> topTenants,
                                  AnalyticsTenantPageVO tenants) {
}
