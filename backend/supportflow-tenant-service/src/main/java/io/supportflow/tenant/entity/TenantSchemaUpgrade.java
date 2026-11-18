package io.supportflow.tenant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * tenant_schema_upgrade 租户库升级记录（控制面）。
 *
 * <p>记录"平台在什么时候把哪个租户的库升到了第几版、结果如何"；
 * 数据面（customer_db.schema_upgrade_log）记"这个库实际执行了哪个脚本"。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tenant_schema_upgrade")
public class TenantSchemaUpgrade {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private String targetSchema;

    private Integer fromVersion;

    private Integer toVersion;

    /** 1-进行中、2-成功、3-失败 */
    private Integer status;

    private Integer scriptCount;

    private String appliedScripts;

    private String message;

    private Integer durationMs;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
