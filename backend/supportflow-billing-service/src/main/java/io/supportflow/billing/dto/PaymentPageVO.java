package io.supportflow.billing.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 收款流水分页 + 合计 + 概览 + 按方式汇总（页面一次请求全拿到）。
 *
 * @param amount 只算**成功收款**（列表上的 status 筛选是给列表用的，不影响合计）
 */
public record PaymentPageVO(long total, int page, int pageSize, List<PaymentRowVO> list,
                            BigDecimal amount, PaymentOverviewVO overview,
                            List<PayMethodSumVO> byMethod) {
}
