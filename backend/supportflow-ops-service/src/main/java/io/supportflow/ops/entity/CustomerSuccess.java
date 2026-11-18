package io.supportflow.ops.entity;

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
 * customer_success 客户成功随访状态（一个租户一行）。

 * <p>只放"人决定的那些事"：客户成功经理、上次/下次回访时间、人工覆盖的续费风险与扩展商机、备注。
 * 健康分是算出来的（数据在 tenant_db），不往这里存，避免落库即过期。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("customer_success")
public class CustomerSuccess {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 健康分快照（人工确认过的值才写；平时为 null，以实时计算为准） */
    private Short healthScore;

    /** 续费风险：1-低、2-中、3-高（人工可覆盖自动判断） */
    private Short renewRisk;

    /** 扩展商机：1-有、2-无 */
    private Short expandOpportunity;

    /** 客户成功经理（平台账号 ID） */
    private Long csOwner;

    @TableField("cs_owner_name")
    private String csOwnerName;

    private LocalDateTime lastVisitTime;

    private LocalDateTime nextVisitTime;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
