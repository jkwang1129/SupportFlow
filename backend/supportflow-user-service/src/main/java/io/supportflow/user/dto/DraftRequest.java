package io.supportflow.user.dto;

/**
 * 企业草稿创建请求。
 */
public record DraftRequest(
        long applicantId,
        String companyName,
        String industry,
        String scale,
        String contactName,
        String contactPhone,
        String contactEmail
) {
}
