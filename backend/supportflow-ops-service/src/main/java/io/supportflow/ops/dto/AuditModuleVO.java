package io.supportflow.ops.dto;

/**
 * 有日志的模块 + 条数（筛选下拉用）。
 */
public record AuditModuleVO(String module, Long cnt) {
}
