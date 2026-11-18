package io.supportflow.tenant.dto;

import java.math.BigDecimal;

/** 套餐变更入参（金额只用于日志与对账，不参与配额计算） */
/**
 * @param trialDays 试用天数（可空）：平台「注册与审核」里配的试用期。
 *                  有值时按"今天 + N 天"算到期，覆盖默认的按周期（月/年）计算——
 *                  试用就是"给你用 14 天"，不是"给你一个月"。
 */
public record PlanBody(long planId, String planName, int cycle, BigDecimal amount,
                       int qpsLimit, int dailyRequestLimit,
                       int dailySessionLimit, int aiDailyLimit, Integer trialDays) {
}
