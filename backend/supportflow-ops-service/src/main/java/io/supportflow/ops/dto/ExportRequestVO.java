package io.supportflow.ops.dto;

/**
 * 提交导出申请的回执。
 *
 * @param duplicated 同一类型已有待审批的申请（没有重复建单）
 */
public record ExportRequestVO(String id, int status, boolean duplicated) {
}
