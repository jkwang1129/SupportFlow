package io.supportflow.tenant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * enterprise_review 企业审核表实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("enterprise_review")
public class EnterpriseReview {

    /** 主键 ID（雪花算法生成） */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 关联企业 ID（申请主体） */
    private Long enterpriseId;

    /** 租户编码（审核通过创建租户后回填） */
    private String tenantCode;

    /** 申请编号 */
    private String applyNo;

    /** 申请版本（驳回重提递增） */
    private Integer versionNo;

    /** 申请人用户 ID */
    private Long applicantId;

    /** 企业名称 */
    private String companyName;

    /** 所属行业 */
    private String industry;

    /** 团队规模 */
    private String scale;

    /** 管理员姓名 */
    private String contactName;

    /** 联系电话 */
    private String contactPhone;

    /** 企业邮箱 */
    private String contactEmail;

    /** 统一社会信用代码 / 营业执照号 */
    private String licenseNo;

    /** 注册地址 */
    private String registerAddress;

    /** 法人代表 */
    private String legalPerson;

    /** 营业执照附件文件 ID */
    private Long licenseFileId;

    /** 状态码：1-待审核、2-已通过、3-已驳回 */
    private Integer status;

    /** 审核人用户 ID */
    private Long reviewerId;

    /** 审核时间 */
    private LocalDateTime reviewTime;

    /** 驳回原因 */
    private String rejectReason;

    /** 提交时间 */
    private LocalDateTime submitTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 创建人 */
    private String creator;

    /** 更新人 */
    private String editor;

    /** 逻辑删除标记 */
    @TableField("is_deleted")
    private Boolean deleted;
}
