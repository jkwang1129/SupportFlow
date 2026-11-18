package io.supportflow.customer.dto.notification;

import java.util.Map;

/**
 * 消息中心分类统计。
 *
 * <p>byType 的键是通知类型码（"1"、"2"…）——**键是数据不是字段**，所以保持 Map；
 * 值是固定结构，抽成了 {@link NotificationTypeCountVO}。</p>
 */
public record NotificationSummaryVO(NotificationCountVO all,
                                    Map<String, NotificationTypeCountVO> byType,
                                    int scanLimit) {
}
