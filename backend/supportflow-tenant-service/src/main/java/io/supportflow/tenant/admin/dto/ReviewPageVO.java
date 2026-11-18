package io.supportflow.tenant.admin.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 企业审核列表行（enterprise_review 关联 enterprise）。
 */
@Data
public class ReviewPageVO {

    private String id;
    private String enterpriseId;
    private String enterpriseCode;
    private String tenantCode;
    private String applyNo;
    private Integer versionNo;
    private String applicantId;
    private String companyName;
    private String industry;
    private String scale;
    private String contactName;
    private String contactPhone;
    private String contactEmail;
    private String licenseNo;
    private String registerAddress;
    private String legalPerson;
    private String licenseFileId;
    /** 审核状态：1-待审核、2-已通过、3-已驳回 */
    private Integer status;
    private String rejectReason;
    private String reviewerId;
    private LocalDateTime reviewTime;
    private LocalDateTime submitTime;
    /** 企业状态：1-待审核、2-正常、3-已驳回 */
    private Integer enterpriseStatus;
}
