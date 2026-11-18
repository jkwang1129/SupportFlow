package io.supportflow.ops.dto;

import java.math.BigDecimal;

/**
 * 告警事件的一行（含指标 / 级别的中文文案）。
 */
public record AlertEventVO(String id, String ruleId, String ruleName, Short metric, Short level,
                           String tenantCode, BigDecimal metricValue, BigDecimal threshold,
                           Short windowDays, String statDate, String detail,
                           Short handleStatus, String handleRemark, String handlerName,
                           String handleTime, Boolean notified, String eventTime,
                           String metricText, String levelText, String handleStatusText) {
}
