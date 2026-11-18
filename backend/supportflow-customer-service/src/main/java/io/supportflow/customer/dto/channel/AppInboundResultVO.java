package io.supportflow.customer.dto.channel;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * App 用户来消息后的回执。
 *
 * <p>幂等命中（同一条 clientMsgNo 重复推）时只回 {@code duplicated + note}，
 * 所以不属于该分支的字段不出现。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppInboundResultVO(Boolean duplicated, String sessionNo, String customerNo,
                                 String welcome, String note) {
}
