package io.supportflow.customer.dto.notification;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 消息中心分页。
 *
 * @param scanLimit 「只看未读」时的退化口径：只在最近 N 条里挑未读（已读是按人存的，
 *                  SQL 里表达不了），不传表示这次是精确计数
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationPageVO(int total, int page, int pageSize,
                                 List<NotificationVO> list, Integer scanLimit) {
}
