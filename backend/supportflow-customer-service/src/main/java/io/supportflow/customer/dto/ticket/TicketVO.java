package io.supportflow.customer.dto.ticket;

/** 工单列表的一行。 */
public record TicketVO(
        /** 租户号：平台侧跨租户列表要显示，企业侧顺带回传（前端不用猜） */
        String tenantCode,
        /** 1-企业内部工单、2-平台支持工单 */
        Integer ticketType,
        String ticketTypeText,
        String ticketNo,
        String title,
        /** 来源渠道（1-在线会话、2-电话热线、3-邮件、4-工单导入、5-其它） */
        Integer sourceChannel,
        String sourceChannelText,
        Integer category,
        String categoryText,
        Integer priority,
        String priorityText,
        Integer status,
        String statusText,
        Integer source,
        String sourceText,
        String sessionNo,
        String customerName,
        Long assigneeId,
        String assigneeName,
        Integer slaState,
        String slaStateText,
        /** 距离下一个截止时间还有多少分钟，负数表示已经超了 */
        Integer remainMinutes,
        boolean firstResponded,
        String firstResponseDue,
        String resolveDue,
        String createTime,
        String creatorName,
        String finishText
) {
}
