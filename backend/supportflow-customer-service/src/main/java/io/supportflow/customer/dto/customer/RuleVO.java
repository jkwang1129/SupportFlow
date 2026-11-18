package io.supportflow.customer.dto.customer;

import io.supportflow.customer.entity.CustomerTagDef;
import io.supportflow.customer.service.customer.CustomerTagRuleService;
import java.math.BigDecimal;

/** 供前端展示的规则标签定义（指标/条件/阈值/窗口） */
public record RuleVO(String tagCode, String tagName, String metric, String metricText,
                     String op, String opText, BigDecimal value, int windowDays, String text) {

    public static RuleVO of(CustomerTagDef def) {
        return new RuleVO(def.getTagCode(), def.getTagName(), def.getRuleMetric(),
                CustomerTagRuleService.metricName(def.getRuleMetric()),
                def.getRuleOp(), CustomerTagRuleService.opName(def.getRuleOp()),
                def.getRuleValue(), def.getRuleWindowDays() == null ? 0 : def.getRuleWindowDays(),
                CustomerTagRuleService.ruleText(def));
    }
}
