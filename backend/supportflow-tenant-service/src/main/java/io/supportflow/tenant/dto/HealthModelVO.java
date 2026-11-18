package io.supportflow.tenant.dto;

/**
 * 健康度模型说明（页面右上角「健康模型」按钮直接用，避免前端再抄一份口径）。
 */
public record HealthModelVO(int usage, int active, int expire, int trend, int expandUsagePercent,
                            int renewWatchDays, String note) {
}
