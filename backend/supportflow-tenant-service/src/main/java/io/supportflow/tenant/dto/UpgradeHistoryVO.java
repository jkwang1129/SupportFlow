package io.supportflow.tenant.dto;

import java.time.LocalDateTime;

/**
 * 租户库升级历史的一条（控制面与数据面各一条，这里读的是控制面记录）。
 */
public record UpgradeHistoryVO(Long id, String targetSchema, Integer fromVersion,
                               Integer toVersion, Integer status, Integer scriptCount,
                               String appliedScripts, String message, Integer durationMs,
                               String operatorName, LocalDateTime startedAt,
                               LocalDateTime finishedAt) {
}
