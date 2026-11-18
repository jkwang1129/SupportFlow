package io.supportflow.customer.dto.quality;

import java.util.List;

/** 质检评估请求：会话转写 + 要跑的规则。 */
public record EvaluateRequest(
        String tenantCode,
        String sessionName,
        String agentName,
        String transcript,
        List<String> ruleNames,
        List<RuleSpec> rules
) {
}
