package io.supportflow.customer.dto.quality;

/**
 * AI 质量总览：质检任务 / 评分 / 风险分布 / 复核率 + 实时预警数。
 */
public record AiQualityOverviewVO(Long qaTasks, Long scoredTasks, double avgAiScore,
                                  Long highRiskTasks, Long midRiskTasks, Long lowRiskTasks,
                                  Long reviewedTasks, Long alerts, Long severeAlerts,
                                  Long warningAlerts, Long pendingAlerts, Long alertTenants,
                                  double reviewRate) {
}
