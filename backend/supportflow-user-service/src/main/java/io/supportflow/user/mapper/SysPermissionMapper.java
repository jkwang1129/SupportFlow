package io.supportflow.user.mapper;

import io.supportflow.user.entity.SysPermission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * sys_permission 权限点 Mapper，SQL 见 SysPermissionMapper.xml。
 */
@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /** 启用中的权限目录（按 sort_no 排） */
    List<SysPermission> selectEnabledPermissions();

    /** 某个角色勾了哪些权限 */
    List<Long> selectPermIdsByRoleId(@Param("roleId") Long roleId);

    /**
     * 某个用户**最终**拥有的权限编码。
     *
     * <p>走 sys_user_role → sys_role_permission → sys_permission 三张表；
     * 停在任一环节断了就是空集合（比如账号没绑角色）。</p>
     */
    List<String> selectPermCodesByUserId(@Param("tenantCode") String tenantCode,
                                         @Param("userId") Long userId);
}
