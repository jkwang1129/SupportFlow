package io.supportflow.billing.dto;

import io.supportflow.billing.entity.BillingOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单分页。
 *
 * @param amount 当前筛选条件下的金额合计（与列表同一套过滤条件，保证"看到的行"和"合计"一致）
 */
public record BillingOrderPageVO(long total, BigDecimal amount, int page, int pageSize,
                                 List<BillingOrder> list) {
}
