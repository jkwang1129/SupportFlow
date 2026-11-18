package io.supportflow.customer.dto.quality;

import java.util.List;

/** 质检评估结果：AI 打分 + 命中的规则 + 评语。 */
    public record EvaluateResult(
        int aiScore,
        int riskLevel,
        List<String> rules,
        String comment,
        String source
) {
}
