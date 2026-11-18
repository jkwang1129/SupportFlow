package io.supportflow.ops.dto;

import java.math.BigDecimal;

/** 预算入参 */
public record BudgetBody(String tenantCode, BigDecimal monthlyBudget, Integer alertRatio,
                         Integer overAction, Boolean isEnabled, String remark) {
}
