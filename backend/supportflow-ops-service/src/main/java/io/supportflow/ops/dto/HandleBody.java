package io.supportflow.ops.dto;

/** 处置入参：status 2-已确认、3-已忽略 */
public record HandleBody(Integer status, String remark) {
}
