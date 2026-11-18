package io.supportflow.customer.dto.quality;

/** 告警查询条件（都为 null 表示不过滤） */
public record AlertQuery(
        Integer status,
        Integer severity,
        Integer ruleType,
        String sessionNo,
        String keyword,
        String startTime,
        String endTime
) {
}
