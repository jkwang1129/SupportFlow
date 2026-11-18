package io.supportflow.tenant.dto;

/**
 * 创建草稿命令。
 */
public record DraftCommand(
        long applicantId,
        String companyName,
        String industry,
        String scale,
        String contactName,
        String contactPhone,
        String contactEmail
) {
}
