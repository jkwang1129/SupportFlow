package io.supportflow.customer.dto.ticket;

/** 平台看板数字（和企业侧同构，前端可以复用同一套卡片样式） */
public record PlatformTicketOverviewVO(int pending, int processing, int confirming, int overdue, int warning,
                         int mine, int resolvedToday) {
}
