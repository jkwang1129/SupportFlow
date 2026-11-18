package io.supportflow.ops.dto;

/**
 * API 密钥校验结果：命中就把租户编码带出来。
 */
public record ApiKeyVerifyVO(boolean valid, String tenantCode) {
}
