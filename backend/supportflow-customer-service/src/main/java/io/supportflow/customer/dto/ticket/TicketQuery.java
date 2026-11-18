package io.supportflow.customer.dto.ticket;

/** 工单列表查询条件。 */
public record TicketQuery(
        Integer status,
        Integer priority,
        Integer category,
        Integer slaState,
        Long assigneeId,
        Boolean mineOnly,
        Boolean unassignedOnly,
        String sessionNo,
        String keyword,
        /** 工单类型：1-企业内部（默认）、2-平台支持；为空按 1 处理 */
        Integer ticketType
) {
}
