package io.supportflow.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * tenant_cost_budget 租户 AI 成本预算。
 *
 * <p>预算不是"限额"而是"经营阀门"：月度花超了要有人知道，还要能决定怎么办——
 * 仅告警 / 降级到规则与检索（不调模型）/ 暂停 AI。**动作必须有执行点**，
 * 落点在 customer-service 调 AI 之前的成本闸门（见 CostGuardClient）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tenant_cost_budget")
public class TenantCostBudget {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 月度 AI 成本预算（元）；0 = 不限 */
    private BigDecimal monthlyBudget;

    /** 达到预算的百分之多少先告警 */
    private Short alertRatio;

    /** 超限动作：1-仅告警、2-降级到规则/检索、3-暂停 AI */
    private Short overAction;

    private Boolean isEnabled;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
