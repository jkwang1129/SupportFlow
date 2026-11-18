package io.supportflow.customer.dto.quality;

import java.time.LocalDateTime;

/** 告警视图（质检中心列表 / 工作台提示共用） */
public record AlertVO(
        String id,
        String sessionNo,
        String agentId,
        String agentName,
        String ruleName,
        int ruleType,
        String ruleTypeText,
        int severity,
        String severityText,
        String hitKeyword,
        String snippet,
        String advice,
        int status,
        String statusText,
        String handlerName,
        String handleRemark,
        Long messageSeq,
        LocalDateTime createTime,
        LocalDateTime handleTime
) {
}
