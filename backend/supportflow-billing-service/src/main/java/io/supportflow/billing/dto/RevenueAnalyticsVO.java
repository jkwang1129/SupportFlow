package io.supportflow.billing.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 平台经营分析：一次把页面要的聚合全给了（省得前端串多个请求）。
 */
public record RevenueAnalyticsVO(String cycleKey, BigDecimal paidTotal, BigDecimal paidThisMonth,
                                 Long paidOrders, Long paidOrdersThisMonth, Long pendingOrders,
                                 BigDecimal pendingAmount, Long payingTenants,
                                 BigDecimal avgOrderAmount, List<RevenueTrendVO> revenueTrend,
                                 List<PlanRevenueVO> planRevenue,
                                 List<PayMethodRevenueVO> payMethodRevenue,
                                 StatementSummaryVO statements) {
}
