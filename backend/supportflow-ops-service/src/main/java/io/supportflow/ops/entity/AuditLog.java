package io.supportflow.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * audit_log 审计日志（只追加，不更新不删除）。
 *
 * <p>注意：这张表**没有** {@code is_deleted}——审计日志删了就没有意义了，
 * 所以实体里也不带逻辑删除字段，MyBatis-Plus 的删除入口在业务上不开放。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("audit_log")
public class AuditLog {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 操作人所属租户（平台操作固定 PLATFORM） */
    private String tenantCode;

    private Long operatorId;

    /** 操作人姓名（写日志时的快照） */
    private String operatorName;

    /** 模块：企业审核 / 用户管理 / 角色权限 / 计费与账单 / 租户管理 */
    private String module;

    /** 操作：通过企业审核 / 停用账号 / 结算账单 … */
    private String action;

    private String targetType;

    private String targetId;

    /** 结果码：1-成功、2-失败 */
    private Integer result;

    private String detail;

    private String ip;

    private String requestId;

    private LocalDateTime auditTime;

    private LocalDateTime createTime;
}
