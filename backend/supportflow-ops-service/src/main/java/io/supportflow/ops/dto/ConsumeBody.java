package io.supportflow.ops.dto;

/** 导出校验入参 */
public record ConsumeBody(String tenantCode, int exportType, int rows) {
}
