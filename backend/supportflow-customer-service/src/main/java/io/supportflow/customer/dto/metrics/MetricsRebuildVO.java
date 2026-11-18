package io.supportflow.customer.dto.metrics;

/**
 * 重算坐席绩效的结果（幂等）。
 *
 * @param date 重算的那一天
 * @param rows 落库的（坐席 × 天）行数
 */
public record MetricsRebuildVO(String date, int rows) {
}
