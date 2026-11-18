package io.supportflow.tenant.dto;

/**
 * 引导页状态。
 */
public record GuideState(
        long enterpriseId,
        String enterpriseCode,
        String stage,
        String companyName,
        String industry,
        String scale,
        String licenseNo,
        String registerAddress,
        String legalPerson,
        String licenseFileId,
        String contactName,
        String contactPhone,
        String contactEmail,
        String applyNo,
        Integer versionNo,
        String rejectReason,
        String submitTime,
        String tenantCode,
        /** 是否由平台策略自动放行（关掉人工审核 / 邮箱域名命中白名单） */
        boolean autoApproved
) {
}
