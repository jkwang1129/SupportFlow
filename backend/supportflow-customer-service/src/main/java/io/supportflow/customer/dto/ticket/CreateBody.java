package io.supportflow.customer.dto.ticket;

/** 工单创建入参。 */
public record CreateBody(
        String title,
        String content,
        Integer category,
        Integer priority,
        Integer source,
        String sessionNo,
        Long assigneeId,
        String assigneeName,
        /** 客户名称：手建工单时必填（会话转单时自动取会话里的客户） */
        String customerName,
        /** 来源渠道：1-在线会话、2-电话热线、3-邮件、4-工单导入、5-其它 */
        Integer sourceChannel
) {
}
