package io.supportflow.customer.dto.quality;

/**
 * 看板概览。
 */
public record OverviewVO(
        int total,
        int pending,
        int passed,
        int rejected,
        double avgAiScore,
        double avgReviewScore,
        double passRate,
        int riskLow,
        int riskMid,
        int riskHigh,
        int alertTotal,
        int alertPending,
        int alertSevere
) {
}
