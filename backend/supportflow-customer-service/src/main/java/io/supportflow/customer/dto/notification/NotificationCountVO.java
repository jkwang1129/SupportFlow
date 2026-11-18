package io.supportflow.customer.dto.notification;

/**
 * 「总数 / 未读」这一对数字。
 */
public record NotificationCountVO(int total, int unread) {
}
