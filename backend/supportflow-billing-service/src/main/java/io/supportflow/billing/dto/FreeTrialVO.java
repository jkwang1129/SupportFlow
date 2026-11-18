package io.supportflow.billing.dto;

/**
 * 开通免费版试用的回执。
 */
public record FreeTrialVO(String tenantCode, Long planId, String planName, boolean trial,
                          Integer trialDays, Integer quotaSessions, boolean tenantPlanSynced) {
}
