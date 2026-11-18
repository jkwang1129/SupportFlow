package io.supportflow.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import io.supportflow.customer.service.tenant.TenantSchemaCatalog;

/**
 * 租户库升级记录（数据面：这个库实际执行到哪一版）。
 *
 * <p>控制面在 tenant_db 的 `tenant_schema_upgrade` 里记"平台下发过什么"，
 * 这张表记"这个库真的执行了哪个脚本、花了多久、成没成"——两边都留痕，
 * 出问题能回答"到底有没有跑过"。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("schema_upgrade_log")
public class SchemaUpgradeLog {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 目标 schema：共享库为 public，独立 Schema 为租户专属 schema 名 */
    private String targetSchema;

    /** 脚本版本号（与 TenantSchemaCatalog 的版本一致） */
    private Integer version;

    private String script;

    private String title;

    /** 1-成功、2-失败 */
    private Integer status;

    private Integer durationMs;

    private String message;

    /** 执行完这一步之后，该库的版本号 */
    private Integer tenantDbVersion;

    private LocalDateTime appliedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除：这张表只追加不删除，字段留着是为了和其它实体保持一致 */
    @TableField(exist = false)
    private Boolean deleted;
}
