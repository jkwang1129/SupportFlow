package io.supportflow.user.dto;

/**
 * 企业草稿创建结果。
 */
public record DraftResponse(long enterpriseId, String enterpriseCode, String tenantCode, int status) {
}
