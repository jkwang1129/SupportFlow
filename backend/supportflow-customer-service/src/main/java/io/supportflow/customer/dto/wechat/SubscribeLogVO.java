package io.supportflow.customer.dto.wechat;

/**
 * 订阅消息发送记录的一行（页面展示"发了没、为什么没发"）。
 */
public record SubscribeLogVO(String id, Short scene, String sceneText, String bizNo,
                             String templateId, Short status, String statusText,
                             Integer errcode, String message, String createTime) {
}
