package io.supportflow.realtime.dto;

/** 消息视图 */
public record MessageView(
        String msgId,
        String msgNo,
        /** 客户端消息号：回 ACK 时带上，客户端据此把"发送中"改成"已发送" */
        String clientMsgNo,
        /** 会话内序号：双方按它排序，也是增量补拉的游标 */
        Long seq,
        Long sessionId,
        Integer senderType,
        Long senderId,
        Integer msgType,
        String content,
        Integer visibleTo,
        String sendTime
) {
}
