package io.supportflow.tenant.dto;

/**
 * 单个租户的健康度分解。
 *
 * @param remainDays 剩余到期天数；没设到期时间时为 null
 * @param isFreePlan 是否免费版（免费版的"用量分"口径不同）
 */
public record TenantHealthVO(String tenantCode, String companyName, String industry, String planId,
                             boolean isFreePlan, int tenantStatus, String tenantStatusText,
                             String expireTime, Long remainDays, Long requests30d,
                             Long sessions30d, Long aiCalls30d, int activeDays30d,
                             Long requestsLast7, Long requestsPrev7, String lastActiveDate,
                             int healthScore, String healthLevel, String healthLevelText,
                             HealthScorePartsVO scoreParts, int renewRisk, String renewRiskText,
                             String renewRiskReason, int expandOpportunity,
                             String expandOpportunityText, String expandSuggestion) {
}
