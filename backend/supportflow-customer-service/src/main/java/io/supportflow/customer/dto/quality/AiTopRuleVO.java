package io.supportflow.customer.dto.quality;

/**
 * 命中最多的质检规则。
 */
public record AiTopRuleVO(String ruleName, Object ruleType, String ruleTypeText,
                          Long alerts, Long severe, String lastHitTime) {
}
