package io.supportflow.customer.dto.notification;

/** 通知中心的一条消息（已读状态是按人算的）。 */
public record NotificationVO(String id, Integer notifyType, String notifyTypeText, String title,
                             String content, String linkView, String publishTime, boolean read) {
}
