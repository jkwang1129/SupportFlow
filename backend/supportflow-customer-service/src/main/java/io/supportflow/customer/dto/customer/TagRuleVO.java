package io.supportflow.customer.dto.customer;

import java.math.BigDecimal;

/** 规则标签清单（前端规则编辑器与"重算"结果展示用） */
public record TagRuleVO(String tagCode, String tagName, String metric, String metricText,
                        String op, String opText, BigDecimal value, int windowDays, String text) {
}
