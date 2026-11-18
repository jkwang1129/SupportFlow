package io.supportflow.ops.dto;

import java.math.BigDecimal;

/** 规则入参 */
public record RuleBody(String ruleName, Integer metric, Integer windowDays, Integer compareOp,
                       BigDecimal threshold, Integer level, Integer scope,
                       Boolean notifyTenant, Boolean isEnabled, String remark) {
}
