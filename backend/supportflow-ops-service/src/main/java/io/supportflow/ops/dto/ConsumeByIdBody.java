package io.supportflow.ops.dto;

/** 按申请单号消费的入参 */
public record ConsumeByIdBody(String tenantCode, long exportId, int rows) {
}
