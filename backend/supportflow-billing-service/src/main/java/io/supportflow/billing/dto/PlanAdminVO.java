package io.supportflow.billing.dto;

import java.math.BigDecimal;

/**
 * 平台「套餐价格」页的一行（含停用套餐：页面要能上下架、改价）。
 */
public record PlanAdminVO(String id, String planCode, String planName,
                          BigDecimal priceYearly, BigDecimal priceMonthly,
                          Integer quotaRobotSessions, Integer quotaSeats, Integer quotaAiCalls,
                          BigDecimal overagePricePerK, Boolean isEnabled) {
}
