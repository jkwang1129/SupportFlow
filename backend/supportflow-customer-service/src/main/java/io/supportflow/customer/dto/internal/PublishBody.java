package io.supportflow.customer.dto.internal;

/** 单租户通知入参（notifyType 与 notification.notify_type 对齐：3-审核通知） */
public record PublishBody(String tenantCode, Integer notifyType, String title, String content,
                          String linkView) {
}
