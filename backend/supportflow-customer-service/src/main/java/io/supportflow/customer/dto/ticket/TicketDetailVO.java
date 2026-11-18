package io.supportflow.customer.dto.ticket;

import java.util.List;

/** 工单详情：工单本体 + 正文 + 流转记录 + SLA。 */
public record TicketDetailVO(TicketVO ticket, String content, List<EventVO> events, SlaVO sla,
                             String tenantCode) {
}
