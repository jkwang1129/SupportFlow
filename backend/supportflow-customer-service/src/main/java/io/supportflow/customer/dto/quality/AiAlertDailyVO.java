package io.supportflow.customer.dto.quality;

/**
 * 按天的预警条数（大屏柱状图的一根）。
 */
public record AiAlertDailyVO(String statDate, Long alerts, Long severe) {
}
