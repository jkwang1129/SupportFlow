package io.supportflow.user.service;

import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.common.audit.AuditLogClient;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.user.dto.CreatedRoleVO;
import io.supportflow.user.dto.PermissionVO;
import io.supportflow.user.dto.RoleVO;
import io.supportflow.user.entity.SysPermission;
import io.supportflow.user.entity.SysRole;
import io.supportflow.user.entity.SysRolePermission;
import io.supportflow.user.entity.SysUserRole;
import io.supportflow.user.mapper.SysPermissionMapper;
import io.supportflow.user.mapper.SysRoleMapper;
import io.supportflow.user.mapper.SysRolePermissionMapper;
import io.supportflow.user.mapper.SysUserRoleMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台角色与权限（原型「角色权限」）。
 *
 * <p>平台角色挂在 {@code tenant_code = PLATFORM} 下，与企业侧角色（各自租户）**互相独立**：
 * 改平台角色的权限不会影响任何企业的成员权限</p>
 *
 * <p>权限口径：{@code perm_code} 就是前端路由 path，所以「保存权限」= 决定这个角色登录后
 * 左侧能看到哪些菜单。</p>
 */
@Service
public class PlatformRoleService {

    private static final Logger log = LoggerFactory.getLogger(PlatformRoleService.class);

    /** 平台角色固定租户 */
    public static final String PLATFORM_TENANT = "PLATFORM";

    /** 平台角色类型码 */
    private static final int ROLE_TYPE_PLATFORM = 1;

    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SnowflakeIdGenerator idGenerator;
    /** 审计日志：角色与权限是"能给谁开什么门"的操作，必须留痕 */
    private final AuditLogClient auditLogClient;

    /** 审计用的模块名（与平台页筛选器一致） */
    private static final String MODULE = "角色权限";

    public PlatformRoleService(SysRoleMapper roleMapper,
                               SysRolePermissionMapper rolePermissionMapper,
                               SysPermissionMapper permissionMapper,
                               SysUserRoleMapper userRoleMapper,
                               SnowflakeIdGenerator idGenerator,
                               AuditLogClient auditLogClient) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.idGenerator = idGenerator;
        this.auditLogClient = auditLogClient;
    }

    /** 平台角色列表（带权限数、绑定人数、权限明细，前端点一行就能直接勾选） */
    public List<RoleVO> list() {
        List<SysRole> roles = roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getTenantCode, PLATFORM_TENANT)
                .eq(SysRole::getDeleted, false)
                .orderByAsc(SysRole::getId));
        List<RoleVO> list = new ArrayList<>(roles.size());
        for (SysRole role : roles) {
            List<Long> permIds = permissionMapper.selectPermIdsByRoleId(role.getId());
            Long userCount = userRoleMapper.selectCount(Wrappers.<SysUserRole>lambdaQuery()
                    .eq(SysUserRole::getRoleId, role.getId()));
            list.add(new RoleVO(String.valueOf(role.getId()), role.getRoleCode(), role.getRoleName(),
                    role.getRemark(), role.getIsFixed(), role.getIsEnabled(),
                    permIds.stream().map(String::valueOf).toList(), permIds.size(),
                    userCount == null ? 0L : userCount));
        }
        return list;
    }

    /** 权限目录（前端「角色与模块权限」的勾选来源，按 sort_no 排） */
    public List<PermissionVO> permissions() {
        List<SysPermission> permissions = permissionMapper.selectEnabledPermissions();
        List<PermissionVO> list = new ArrayList<>(permissions.size());
        for (SysPermission permission : permissions) {
            list.add(new PermissionVO(String.valueOf(permission.getId()), permission.getPermCode(),
                    permission.getPermName(), permission.getPermType(),
                    String.valueOf(permission.getParentId()), permission.getSortNo()));
        }
        return list;
    }

    /** 新增平台角色（可同时勾权限） */
    @Transactional
    public CreatedRoleVO create(LoginUser operator, String roleCode, String roleName,
                                String remark, List<Long> permIds) {
        String code = require(roleCode, "角色编码").toUpperCase();
        String name = require(roleName, "角色名称");
        Long exists = roleMapper.selectCount(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getTenantCode, PLATFORM_TENANT)
                .eq(SysRole::getRoleCode, code)
                .eq(SysRole::getDeleted, false));
        if (exists != null && exists > 0) {
            throw new BizException(40001, "角色编码已存在：" + code);
        }
        long id = idGenerator.nextId();
        LocalDateTime now = LocalDateTime.now();
        roleMapper.insert(SysRole.builder()
                .id(id)
                .tenantCode(PLATFORM_TENANT)
                .roleCode(code)
                .roleName(name)
                .roleType(ROLE_TYPE_PLATFORM)
                .isFixed(false)
                .isEnabled(true)
                .remark(remark)
                .createTime(now)
                .updateTime(now)
                .creator("PLATFORM_ADMIN")
                .deleted(false)
                .build());
        // 建角色时"创建 + 勾权限"是一步操作：这里只落库，不单独记审计
        // （上面那条"新建角色"的详情里已经写了权限数）
        savePermissionsCore(id, permIds);
        log.info("新建平台角色 code={} name={} 权限数={}", code, name,
                permIds == null ? 0 : permIds.size());
        auditLogClient.record(operator, MODULE, "新建角色", "角色", code, true,
                "新建平台角色「" + name + "」，菜单权限 " + (permIds == null ? 0 : permIds.size()) + " 个");
        return new CreatedRoleVO(String.valueOf(id), code, name);
    }

    /** 改角色名称 / 备注 / 启用状态（编码与内置标记不可改） */
    @Transactional
    public void update(LoginUser operator, long roleId, String roleName, String remark, Boolean enabled) {
        SysRole role = requireRole(roleId);
        SysRole update = new SysRole();
        update.setId(role.getId());
        update.setRoleName(require(roleName, "角色名称"));
        update.setRemark(remark);
        if (enabled != null) {
            if (Boolean.TRUE.equals(role.getIsFixed()) && !enabled) {
                throw new BizException(40001, "内置角色不能停用（否则平台可能没人能管）");
            }
            update.setIsEnabled(enabled);
        }
        update.setUpdateTime(LocalDateTime.now());
        update.setEditor("PLATFORM_ADMIN");
        roleMapper.updateById(update);
        auditLogClient.record(operator, MODULE, "修改角色", "角色", role.getRoleCode(), true,
                "角色「" + update.getRoleName() + "」"
                        + (enabled == null ? "资料已更新" : (enabled ? "已启用" : "已停用")));
    }

    /** 删除角色（内置角色不能删；还有人绑着也不能删） */
    @Transactional
    public void remove(LoginUser operator, long roleId) {
        SysRole role = requireRole(roleId);
        if (Boolean.TRUE.equals(role.getIsFixed())) {
            throw new BizException(40001, "内置角色不能删除");
        }
        Long bound = userRoleMapper.selectCount(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getRoleId, roleId));
        if (bound != null && bound > 0) {
            throw new BizException(40001, "还有 " + bound + " 个账号在用这个角色，请先改掉他们的角色");
        }
        rolePermissionMapper.delete(Wrappers.<SysRolePermission>lambdaQuery()
                .eq(SysRolePermission::getRoleId, roleId));
        roleMapper.deleteById(roleId);
        log.info("删除平台角色 id={} code={}", roleId, role.getRoleCode());
        auditLogClient.record(operator, MODULE, "删除角色", "角色", role.getRoleCode(), true,
                "删除平台角色「" + role.getRoleName() + "」");
    }

    /**
     * 保存角色权限（全量覆盖）。
     *
     * <p>平台管理员（内置 PLATFORM_ADMIN）不允许被改权限：它是「谁都进不来」时的最后一道门。</p>
     */
    @Transactional
    public int savePermissions(LoginUser operator, long roleId, List<Long> permIds) {
        SysRole role = requireRole(roleId);
        if (Boolean.TRUE.equals(role.getIsFixed())) {
            throw new BizException(40001, "内置角色「" + role.getRoleName() + "」的权限固定，不能修改");
        }
        int saved = savePermissionsCore(roleId, permIds);
        log.info("保存平台角色权限 roleId={} 权限数={}", roleId, saved);
        auditLogClient.record(operator, MODULE, "保存角色权限", "角色", role.getRoleCode(), true,
                "角色「" + role.getRoleName() + "」的菜单权限改为 " + saved + " 个");
        return saved;
    }

    /** 权限落库本身（全量覆盖）——审计在调用方记，避免"建角色"重复记两条 */
    private int savePermissionsCore(long roleId, List<Long> permIds) {
        rolePermissionMapper.delete(Wrappers.<SysRolePermission>lambdaQuery()
                .eq(SysRolePermission::getRoleId, roleId));
        int saved = 0;
        if (permIds != null) {
            for (Long permId : permIds.stream().distinct().toList()) {
                SysPermission permission = permissionMapper.selectById(permId);
                if (permission == null || Boolean.TRUE.equals(permission.getDeleted())) {
                    throw new BizException(40001, "权限不存在：" + permId);
                }
                rolePermissionMapper.insert(SysRolePermission.builder()
                        .id(idGenerator.nextId())
                        .tenantCode(PLATFORM_TENANT)
                        .roleId(roleId)
                        .permId(permId)
                        .createTime(LocalDateTime.now())
                        .build());
                saved++;
            }
        }
        return saved;
    }

    /**
     * 某账号最终拥有的平台权限编码。
     *
     * <p>没有绑定任何平台角色 → 返回 {@code ["*"]}（按超管处理）。
     * 这是刻意的兜底：菜单按权限过滤，如果历史账号没角色就一个菜单都看不到，
     * 等于把自己锁在门外。绑了角色就严格按角色的权限来（可以是什么都看不到）。</p>
     */
    public List<String> permissionsOf(long userId) {
        try {
            Long roleCount = userRoleMapper.selectCount(Wrappers.<SysUserRole>lambdaQuery()
                    .eq(SysUserRole::getTenantCode, PLATFORM_TENANT)
                    .eq(SysUserRole::getUserId, userId));
            if (roleCount == null || roleCount == 0) {
                return List.of("*");
            }
            return permissionMapper.selectPermCodesByUserId(PLATFORM_TENANT, userId);
        } catch (Exception e) {
            // 还没跑 user_db_platform.sql 时表可能不存在。这里绝不能把登录流程带崩：
            // 退回超管语义（菜单全显示），玩家点进「用户管理」再看到真实报错即可。
            log.warn("查询平台权限失败（是否忘了执行 scripts/migrate-user-db.sh？）：{}", e.getMessage());
            return List.of("*");
        }
    }

    /** 某账号在指定租户下绑定的角色编码（平台账号传 PLATFORM；企业账号传自己的租户） */
    public List<String> roleCodesOf(String tenantCode, long userId) {
        List<Long> roleIds = userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getTenantCode, tenantCode)
                        .eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).distinct().toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectBatchIds(roleIds).stream()
                .filter(role -> !Boolean.TRUE.equals(role.getDeleted()))
                .map(SysRole::getRoleCode)
                .toList();
    }

    private SysRole requireRole(long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null || Boolean.TRUE.equals(role.getDeleted())
                || !PLATFORM_TENANT.equals(role.getTenantCode())) {
            throw new BizException(40401, "平台角色不存在：" + roleId);
        }
        return role;
    }

    private static String require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new BizException(40001, label + "不能为空");
        }
        return value.trim();
    }
}
