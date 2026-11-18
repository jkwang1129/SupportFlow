package io.supportflow.tenant.dto;

/**
 * 企业资料（提交/暂存）。
 */
public record ProfileRequest(
        String companyName,
        String industry,
        String scale,
        String licenseNo,
        String registerAddress,
        String legalPerson,
        Long licenseFileId,
        String contactName,
        String contactPhone,
        String contactEmail
) {
}
