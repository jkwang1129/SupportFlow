package io.supportflow.ops.dto;

/**
 * API 密钥列表项（明文不落库、也不回显；这里只有前缀）。
 */
public record ApiKeyVO(String id, String tenantCode, String keyName, String keyPrefix,
                       String permissions, Integer status, String statusText,
                       Long callCount, String lastUsedAt, String expireTime,
                       String createTime, String creator) {
}
