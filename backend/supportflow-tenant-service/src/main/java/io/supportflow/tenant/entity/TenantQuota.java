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
 * tenant_quota 租户限流与配额。
 *
 * <p>网关按它限流（QPS + 日额度），平台侧可以按套餐调；0 表示"不限"。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tenant_quota")
public class TenantQuota {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 每秒请求上限（0 = 不限流） */
    private Integer qpsLimit;

    /** 每天请求总上限（0 = 不限） */
    private Integer dailyRequestLimit;

    /** 每天新开会话上限（0 = 不限） */
    private Integer dailySessionLimit;

    /** 每天 AI 调用上限（0 = 不限） */
    private Integer aiDailyLimit;

    /** 是否启用限流 */
    private Boolean enabled;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
