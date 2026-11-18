package io.supportflow.user.entity;

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
 * sys_permission 权限点/菜单表。
 *
 * <p>权限编码（{@code perm_code}）直接等于**前端路由 path**（如 {@code /platform/tenants}），
 * 平台侧菜单只要拿 {@code route.path} 比对就能决定显不显示，不用额外维护映射表。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_permission")
public class SysPermission {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 权限编码（本期 = 前端路由 path） */
    private String permCode;

    /** 权限名称（菜单名） */
    private String permName;

    /** 类型码：1-菜单、2-按钮、3-接口 */
    private Integer permType;

    /** 父权限 ID（0 表示顶层） */
    private Long parentId;

    /** 排序 */
    private Integer sortNo;

    /** 是否启用 */
    @TableField("is_enabled")
    private Boolean enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
