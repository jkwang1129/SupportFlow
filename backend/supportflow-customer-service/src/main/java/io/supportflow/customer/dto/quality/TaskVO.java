package io.supportflow.customer.dto.quality;

import java.util.List;

/**
 * 任务视图。
 */
public record TaskVO(
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
        /** 会话期间的实时预警条数（列表上挂个标记，点进详情看明细） */
        int alertCount,
        int alertSevere
) {
}
