package io.supportflow.ops.entity;

import io.supportflow.common.config.JsonStringTypeHandler;
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
 * data_export 数据导出审批（一行 = 一次"申请 → 审批 → 实际导出"）。

 * <p>为什么要把实际导出写回同一行：出事时要能回答"这次申请被批了，最后到底导了多少条、
 * 什么时候导的"——把申请和实际导出写在两张表里，早晚对不上。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "data_export", autoResultMap = true)
public class DataExport {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 导出类型：1-客户档案、2-会话记录、3-工单、4-其它 */
    private Short exportType;

    /**
     * 申请时的筛选条件快照（JSON）。
     *
     * <p>列是 {@code json}，必须走 {@link JsonStringTypeHandler}——不写它 Postgres 会报
     * "column \"scope_json\" is of type json but expression is of type character varying"
     * （register_config.config_json、billing 的账单快照都踩过同一个坑）。</p>
     */
    @TableField(value = "scope_json", typeHandler = JsonStringTypeHandler.class)
    private String scopeJson;

    private Long fileId;

    private Long requesterId;

    private Long approverId;

    /** 1-待审批、2-已通过、3-已驳回 */
    private Short status;

    private String rejectReason;

    private LocalDateTime requestTime;

    private LocalDateTime approveTime;

    /** 实际导出的行数与时间（批准后真导时才写） */
    private Integer exportRows;

    private LocalDateTime exportTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
