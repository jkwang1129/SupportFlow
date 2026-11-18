package io.supportflow.customer.dto.channel;

import java.math.BigDecimal;

/**
 * 今日通话概况：总数 / 已完成 / 转人工 / 未接 / 平均时长（秒）。
 */
public record CallSummaryVO(Long total, Long finished, Long transferred, Long missed,
                            BigDecimal avgDuration) {
}
