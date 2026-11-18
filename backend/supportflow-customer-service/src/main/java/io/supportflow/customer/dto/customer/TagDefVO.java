package io.supportflow.customer.dto.customer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 标签体系的一行（含使用量） */
public record TagDefVO(
        String id,
        String tagCode,
        String tagName,
        String tagGroup,
        String color,
        Integer tagType,
        String tagTypeText,
        String ruleHint,
        String ruleMetric,
        String ruleMetricText,
        String ruleOp,
        BigDecimal ruleValue,
        Integer ruleWindowDays,
        /** 规则的人话描述：累计会话数 ≥ 5（全周期） */
        String ruleText,
        String description,
        Integer sortNo,
        Boolean enabled,
        Integer customerCount,
        LocalDateTime createTime) {
}
