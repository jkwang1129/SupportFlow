package io.supportflow.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import io.supportflow.common.config.JsonStringTypeHandler;

// billing_statement：账期账单（套餐费 + 超量费 = 应付）。
// 同租户同账期唯一，所以"生成账单"是幂等的：重复点只会重算，不会多出两张。
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/** autoResultMap = true：usage_snapshot 用了自定义 typeHandler，查询时才会走它 */
@TableName(value = "billing_statement", autoResultMap = true)
public class BillingStatement {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private String statementNo;

    /** 账期：YYYY-MM */
    private String cycleKey;

    private Long planId;

    /** 套餐名快照（改了套餐名，历史账单也说得清当时是什么套餐） */
    private String planName;

    private BigDecimal planAmount;

    private BigDecimal overageAmount;

    private BigDecimal totalAmount;

    /** 1-待结算、2-已结算、3-已逾期、4-已作废 */
    private Integer status;

    private LocalDate dueDate;

    private LocalDateTime settleTime;

    /**
     * 生成账单时的用量快照（JSON）。
     *
     * <p>列是 {@code json} 类型，实体里是 JSON 字符串，靠 {@link JsonStringTypeHandler}
     * 绑定参数——不写它 Postgres 会报 "column is of type json but expression is of type
     * character varying"，账单插不进去。</p>
     */
    @TableField(value = "usage_snapshot", typeHandler = JsonStringTypeHandler.class)
    private String usageSnapshot;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
