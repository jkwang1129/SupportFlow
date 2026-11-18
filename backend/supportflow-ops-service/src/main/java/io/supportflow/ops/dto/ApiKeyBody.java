package io.supportflow.ops.dto;

/** 密钥入参 */
public record ApiKeyBody(String tenantCode, String keyName, String permissions,
                         String expireTime) {
}
