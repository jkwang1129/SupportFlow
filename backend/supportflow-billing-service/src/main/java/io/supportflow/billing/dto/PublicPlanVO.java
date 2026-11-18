package io.supportflow.billing.dto;

import java.math.BigDecimal;

/**
 * 官网定价页用的套餐项（不需要登录）。
 *
 * <p>字段与后台开通页共用同一套数据，避免官网写死价格后和后台对不上。</p>
 */
public record PublicPlanVO(String planCode, String planName,
                           BigDecimal priceYearly, BigDecimal priceMonthly,
                           Integer quotaRobotSessions, Integer quotaChannels, Integer quotaSeats,
                           Integer quotaStorageGb, Integer quotaAiCalls,
                           Boolean quotaAiQa, Boolean quotaVision,
                           BigDecimal overagePricePerK) {
}
