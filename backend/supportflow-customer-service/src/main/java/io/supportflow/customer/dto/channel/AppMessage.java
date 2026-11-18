package io.supportflow.customer.dto.channel;

/** App 消息载荷 */
public record AppMessage(String userId, String userName, String content, String clientMsgNo) {
}
