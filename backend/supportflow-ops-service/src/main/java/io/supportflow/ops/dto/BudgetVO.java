package io.supportflow.ops.dto;

import java.math.BigDecimal;

/**
 * 成本预算的一行：租户 + 本月预算 + 已用进度。
 *
 * <p>企业名不在这里拼：平台页面本来就有带企业名的租户下拉。</p>
 */
public record BudgetVO(String id, String tenantCode, BigDecimal monthlyBudget, Short alertRatio,
                       Short overAction, String overActionText, Boolean isEnabled, String remark,
                       String updateTime, BigDecimal usedCost, BigDecimal usedRatio,
                       Boolean usageAvailable) {
}
