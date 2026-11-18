package io.supportflow.customer.dto.quality;

import java.util.List;
import java.util.Map;

/**
 * 任务详情。
 */
public record TaskDetailVO(
        String id,
        String taskNo,
        String sessionName,
        String agentName,
        Double aiScore,
        Double reviewScore,
        int riskLevel,
        String riskText,
        int status,
        String statusText,
        String createTime,
        String reviewTime,
        String aiComment,
        List<String> ruleNames,
        List<RuleResultVO> ruleResults,
        String reviewerId,
        /** 会话期间的实时预警（边聊边检留下的证据） */
        List<Map<String, Object>> alerts,
        int alertCount,
        int alertSevere
) {
}
