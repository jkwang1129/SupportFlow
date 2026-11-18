package io.supportflow.customer.dto.channel;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 邮件进线的回执：会话 + 工单 + 意图 + SLA 截止时间 + 回执是否发出。
 *
 * <p>SLA 截止时间一并回给调用方（网关页面上直接能看到"这单最晚什么时候必须响应/解决"）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MailInboundResultVO(Boolean duplicated, String sessionNo, String customerNo,
                                  String ticketNo, String intent, Integer priority,
                                  String firstResponseDue, String resolveDue,
                                  Boolean autoReplySent, String note) {
}
