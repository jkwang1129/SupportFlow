package io.supportflow.customer.dto.metrics;

import java.math.BigDecimal;

/**
 * 租户级按天趋势（大屏首页）。
 *
 * <p>比坐席下钻的 {@link DayPointVO} 多两列：机器人接待量与好评条数——
 * 首页的"会话趋势 / 满意度趋势"两张图靠它们画。</p>
 */
public record TrendPointVO(String day, int sessionCount, int botCount, int messageCount,
                           int csatCount, int goodCsatCount, BigDecimal csatScore,
                           int firstResponseSeconds) {
}
