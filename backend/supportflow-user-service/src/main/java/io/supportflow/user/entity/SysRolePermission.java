package io.supportflow.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * sys_role_permission 角色权限关联表。
 *
 * <p>保存权限是「全量覆盖」语义：先按 role_id 删干净，再按提交的勾选重新插。
 * 这样前端只要把当前勾选的集合传上来即可，不用算差异。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_role_permission")
public class SysRolePermission {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 租户编码（平台角色固定 PLATFORM） */
    private String tenantCode;

    private Long roleId;

    private Long permId;

    private LocalDateTime createTime;
}
