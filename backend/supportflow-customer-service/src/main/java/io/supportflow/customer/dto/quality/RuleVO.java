package io.supportflow.customer.dto.quality;

/**
 * 规则视图。
 */
public record RuleVO(
        String id,
        String ruleName,
        int ruleType,
        String ruleTypeText,
        String ruleContent,
        int weight,
        boolean enabled,
        boolean realtime,
        String hitKeywords,
        int severity,
        int timeoutSeconds,
        String updateTime
) {
}
