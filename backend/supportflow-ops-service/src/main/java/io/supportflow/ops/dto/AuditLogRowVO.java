package io.supportflow.ops.dto;

import java.time.LocalDateTime;

/**
 * 审计日志的一行。
 */
public record AuditLogRowVO(String id, String tenantCode, String operatorId, String operatorName,
                            String module, String action, String targetType, String targetId,
                            Integer result, String detail, String ip, String requestId,
                            LocalDateTime auditTime) {
}
