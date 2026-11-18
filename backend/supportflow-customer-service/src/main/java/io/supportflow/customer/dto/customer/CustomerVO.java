package io.supportflow.customer.dto.customer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 列表行（同时也是导出的一行） */
public record CustomerVO(
        String customerNo,
        String name,
        String phone,
        Integer level,
        String levelText,
        Integer customerType,
        String customerTypeText,
        Integer riskLevel,
        String riskText,
        String channel,
        Integer sessionCount,
        Integer humanCount,
        Integer ticketCount,
        BigDecimal csat,
        String sentimentText,
        String lastIntent,
        String lastEmotion,
        String lastSessionNo,
        LocalDateTime lastSessionAt,
        LocalDateTime lastActive,
        String remark,
        List<TagVO> tags,
        /** 匿名化时间：非空表示这个人已经被抹掉 PII */
        LocalDateTime anonymizedAt,
        /** 被合并到哪个客户编号：非空表示这条是重复档案 */
        String mergedInto,
        LocalDateTime createTime) {
}
