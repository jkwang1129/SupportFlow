package io.supportflow.user.mapper;

import io.supportflow.user.entity.SysRolePermission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_role_permission 角色权限关联 Mapper（标准 CRUD，保存权限时按 role_id 全量覆盖）。
 */
@Mapper
public interface SysRolePermissionMapper extends BaseMapper<SysRolePermission> {
}
