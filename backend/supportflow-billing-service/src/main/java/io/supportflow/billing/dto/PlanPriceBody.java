package io.supportflow.billing.dto;

import java.math.BigDecimal;

/** 套餐价格入参 */
public record PlanPriceBody(String id, BigDecimal priceYearly, BigDecimal priceMonthly,
                            Integer quotaRobotSessions, Integer quotaSeats, Integer quotaAiCalls,
                            BigDecimal overagePricePerK, Boolean isEnabled) {
}
