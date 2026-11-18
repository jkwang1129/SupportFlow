package io.supportflow.customer.dto.session;

import java.time.LocalDateTime;

/** 消息 */
public record MessageVO(
        String msgId,
        String msgNo,
        /** 客户端消息号：前端据此把"发送中"的气泡换成"已发送" */
        String clientMsgNo,
        /** 会话内序号：双方按它排序，也是增量补拉的游标 */
        Long seq,
        Long sessionId,
        Integer senderType,
        Long senderId,
        Integer msgType,
        String content,
        Integer visibleTo,
        LocalDateTime sendTime
) {
}
