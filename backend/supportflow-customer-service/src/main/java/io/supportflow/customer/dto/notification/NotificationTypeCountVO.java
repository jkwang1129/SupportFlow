package io.supportflow.customer.dto.notification;

/**
 * 某一类通知的条数（消息中心左侧分类）。
 */
public record NotificationTypeCountVO(String text, int total, int unread) {
}
