package io.supportflow.ops.dto;

/**
 * 导出申请单概览。
 */
public record ExportSummaryVO(int pending, int approved, int rejected) {
}
