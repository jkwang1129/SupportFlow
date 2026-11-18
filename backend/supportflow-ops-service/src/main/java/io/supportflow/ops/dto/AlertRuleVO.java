package io.supportflow.ops.dto;

import java.math.BigDecimal;

/**
 * 告警规则的一行（阈值 / 窗口 / 严重级 / 生效范围）。
 */
public record AlertRuleVO(String id, String ruleName, Short metric, String metricText,
                          Short windowDays, Short compareOp, String compareText,
                          BigDecimal threshold, Short level, String levelText,
                          Short scope, String scopeText, Boolean notifyTenant,
                          Boolean isEnabled, String remark, String updateTime) {
}
