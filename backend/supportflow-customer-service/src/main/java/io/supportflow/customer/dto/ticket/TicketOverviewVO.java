package io.supportflow.customer.dto.ticket;

/** 工单看板数字：各状态计数 + 今日解决 / 超时 / 即将超时。 */
public record TicketOverviewVO(
        int pending,
        int processing,
        int confirming,
        int overdue,
        int warning,
        int mine,
        int resolvedToday
) {
}
