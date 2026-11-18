package io.supportflow.customer.dto.ticket;

/** 渠道自动建单入参 */
public record ChannelTicketCommand(String title, String content, Integer category, Integer priority,
                                  Long customerId, String customerName, Integer sourceChannel,
                                  String sessionNo, Long sourceSessionId,
                                  Long assigneeId, String assigneeName) {
}
