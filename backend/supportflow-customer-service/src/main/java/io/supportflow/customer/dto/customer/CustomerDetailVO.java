package io.supportflow.customer.dto.customer;

import java.util.List;

/** 画像：客户 + 关联工单（本来还有"最近订单"，因为订单属于交易系统、客服侧拿不到可靠数据，已移除） */
public record CustomerDetailVO(CustomerVO customer, List<TicketBriefVO> tickets) {
}
