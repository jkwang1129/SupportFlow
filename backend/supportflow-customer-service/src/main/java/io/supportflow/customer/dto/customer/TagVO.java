package io.supportflow.customer.dto.customer;

import java.time.LocalDateTime;

/** 客户身上的一个标签 */
public record TagVO(
        String tagId,
        String tagName,
        String tagGroup,
        String color,
        Integer tagType,
        Integer source,
        String sourceText,
        String operatorName,
        LocalDateTime createTime) {
}
