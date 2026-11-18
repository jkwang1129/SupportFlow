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
 * tenant 租户表实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tenant")
public class Tenant {

    /** 主键 ID（雪花算法生成） */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 租户编码（对外编码，定长 16） */
    private String tenantCode;

    /** 关联企业 ID */
    private Long enterpriseId;

    /** 当前套餐 ID */
    private Long planId;

    /** 状态码：1-正常、2-冻结、3-到期、4-注销 */
    private Integer status;

    /** 套餐到期时间 */
    private LocalDateTime expireTime;

    /** 隔离模式码：1-共享库、2-独立 Schema、3-独立实例 */
    private Integer isolationMode;

    /** 独立 Schema 模式下的库 schema 名（共享库模式为空，用 public） */
    private String schemaName;

    /** 该租户的库当前版本号（与 TenantSchemaCatalog 的版本一致；0 表示还没登记过） */
    private Integer dbVersion;

    /** 最近一次成功升级的时间 */
    private LocalDateTime upgradedAt;

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
