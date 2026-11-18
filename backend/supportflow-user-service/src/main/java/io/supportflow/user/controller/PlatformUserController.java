package io.supportflow.user.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.api.PageResult;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.user.dto.AssignableRoleVO;
import io.supportflow.user.dto.CreateBody;
import io.supportflow.user.dto.CreatedAccountVO;
import io.supportflow.user.dto.PasswordBody;
import io.supportflow.user.dto.PlatformUserRow;
import io.supportflow.user.dto.RolesBody;
import io.supportflow.user.dto.UpdateBody;
import io.supportflow.user.service.PlatformAccountService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 平台账号中心（原型「用户管理」）：平台管理员与企业用户统一管理。
 *
 * <p>整个控制器都要求调用方是**平台账号**（user_type=1）。企业账号即使猜到路径也进不来——
 * 这里是平台运营域，不该让企业看到别家的账号。</p>
 */
@RestController
@RequestMapping("/api/user/platform/users")
public class PlatformUserController {

    private final PlatformAccountService accountService;

    public PlatformUserController(PlatformAccountService accountService) {
        this.accountService = accountService;
    }

    /** 账号列表：可按账号类型 / 状态 / 租户 / 关键字筛选 */
    @GetMapping
    public ApiResponse<PageResult<PlatformUserRow>> list(
            @RequestParam(required = false) Integer userType,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String tenantCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        requirePlatform();
        return ApiResponse.ok(accountService.page(userType, status, tenantCode, keyword,
                Math.max(1, page), Math.min(Math.max(1, pageSize), 100)));
    }

    /** 可分配角色：平台账号看平台角色，企业账号看该租户的企业角色 */
    @GetMapping("/roles")
    public ApiResponse<List<AssignableRoleVO>> roles(
            @RequestParam(required = false) String tenantCode
    ) {
        requirePlatform();
        return ApiResponse.ok(accountService.assignableRoles(tenantCode));
    }

    /** 新建账号（返回一次性初始密码） */
    @PostMapping
    public ApiResponse<CreatedAccountVO> create(
            @RequestBody CreateBody body
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(accountService.create(operator, body));
    }

    /** 改资料 */
    @PutMapping("/{userId}")
    public ApiResponse<Void> update(
            @PathVariable long userId,
            @RequestBody UpdateBody body
    ) {
        LoginUser operator = requirePlatform();
        accountService.update(operator, userId, body);
        return ApiResponse.ok();
    }

    /** 启用 / 停用 */
    @PostMapping("/{userId}/status")
    public ApiResponse<Void> updateStatus(
            @PathVariable long userId,
            @RequestParam int status
    ) {
        LoginUser operator = requirePlatform();
        accountService.updateStatus(operator, userId, status);
        return ApiResponse.ok();
    }

    /** 重置密码 */
    @PostMapping("/{userId}/password")
    public ApiResponse<Void> resetPassword(
            @PathVariable long userId,
            @RequestBody PasswordBody body
    ) {
        LoginUser operator = requirePlatform();
        accountService.resetPassword(operator, userId, body.password());
        return ApiResponse.ok();
    }

    /** 分配角色（全量覆盖） */
    @PutMapping("/{userId}/roles")
    public ApiResponse<Void> setRoles(
            @PathVariable long userId,
            @RequestBody RolesBody body
    ) {
        LoginUser operator = requirePlatform();
        accountService.setRoles(operator, userId, body.roleIds());
        return ApiResponse.ok();
    }

    /** 导出账号 CSV（带 UTF-8 BOM，Excel 直接打开不乱码） */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) Integer userType,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String tenantCode,
            @RequestParam(required = false) String keyword
    ) {
        requirePlatform();
        byte[] body = accountService.exportCsv(userType, status, tenantCode, keyword)
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"platform-users.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    private LoginUser requirePlatform() {
        LoginUser user = LoginUserContext.require();
        if (user.userType() != 1) {
            throw new BizException(40302, "只有平台账号能管理平台用户");
        }
        return user;
    }
}
