package io.supportflow.user.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.api.SavedCountVO;
import io.supportflow.user.dto.CreatedRoleVO;
import io.supportflow.user.dto.PermissionBody;
import io.supportflow.user.dto.PermissionVO;
import io.supportflow.user.dto.RoleBody;
import io.supportflow.user.dto.RoleVO;
import io.supportflow.user.service.PlatformRoleService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台角色与权限（原型「角色权限」）。
 *
 * <p>与企业侧角色互相独立：这里只管 {@code tenant_code = PLATFORM} 的角色，
 * 改它不会影响任何企业成员的权限。</p>
 */
@RestController
@RequestMapping("/api/user/platform/roles")
public class PlatformRoleController {

    private final PlatformRoleService roleService;

    public PlatformRoleController(PlatformRoleService roleService) {
        this.roleService = roleService;
    }

    /** 平台角色列表（带权限数与绑定人数） */
    @GetMapping
    public ApiResponse<List<RoleVO>> list() {
        requirePlatform();
        return ApiResponse.ok(roleService.list());
    }

    /** 权限目录（菜单/按钮/接口），前端据此渲染勾选树 */
    @GetMapping("/permissions")
    public ApiResponse<List<PermissionVO>> permissions() {
        requirePlatform();
        return ApiResponse.ok(roleService.permissions());
    }

    /** 新增平台角色 */
    @PostMapping
    public ApiResponse<CreatedRoleVO> create(
            @RequestBody RoleBody body
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(roleService.create(operator, body.roleCode(), body.roleName(),
                body.remark(), body.permIds()));
    }

    /** 改角色名称 / 备注 / 启用状态 */
    @PutMapping("/{roleId}")
    public ApiResponse<Void> update(
            @PathVariable long roleId,
            @RequestBody RoleBody body
    ) {
        LoginUser operator = requirePlatform();
        roleService.update(operator, roleId, body.roleName(), body.remark(), body.isEnabled());
        return ApiResponse.ok();
    }

    /** 保存角色权限（全量覆盖） */
    @PutMapping("/{roleId}/permissions")
    public ApiResponse<SavedCountVO> savePermissions(
            @PathVariable long roleId,
            @RequestBody PermissionBody body
    ) {
        LoginUser operator = requirePlatform();
        int saved = roleService.savePermissions(operator, roleId, body.permIds());
        return ApiResponse.ok(new SavedCountVO(saved));
    }

    /** 删除角色（内置角色与仍被使用的角色不能删） */
    @DeleteMapping("/{roleId}")
    public ApiResponse<Void> remove(
            @PathVariable long roleId
    ) {
        LoginUser operator = requirePlatform();
        roleService.remove(operator, roleId);
        return ApiResponse.ok();
    }

    private LoginUser requirePlatform() {
        LoginUser user = LoginUserContext.require();
        if (user.userType() != 1) {
            throw new BizException(40302, "只有平台账号能管理平台角色");
        }
        return user;
    }
}
