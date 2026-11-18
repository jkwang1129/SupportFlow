package io.supportflow.ops.dto;

import java.util.List;

/**
 * 审计日志分页。
 */
public record AuditLogPageVO(long total, int page, int pageSize, List<AuditLogRowVO> list) {
}
