package io.supportflow.user.controller;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.user.dto.BackfillBody;
import io.supportflow.user.dto.UserNameVO;
import io.supportflow.user.service.MemberInviteService;
import io.supportflow.user.service.UserInternalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内部接口：仅供 tenant-service 审核通过后回填用户租户编码。
 */
@RestController
@RequestMapping("/api/user/internal")
public class UserInternalController {

    private final UserInternalService userInternalService;
    private final MemberInviteService memberInviteService;

    public UserInternalController(UserInternalService userInternalService,
                                  MemberInviteService memberInviteService) {
        this.userInternalService = userInternalService;
        this.memberInviteService = memberInviteService;
    }

    /**
     * 回填用户主归属租户编码。
     */
    @PutMapping("/users/{userId}/tenant-code")
    public ApiResponse<Void> updateTenantCode(
            @PathVariable long userId,
            @RequestBody BackfillBody body
    ) {
        userInternalService.updateTenantCode(userId, body.tenantCode());
        return ApiResponse.ok();
    }

    /**
     * 批量查询用户姓名（内部接口：跨服务展示创建人姓名）。
     */
    @GetMapping("/users/names")
    public ApiResponse<List<UserNameVO>> userNames(@RequestParam String ids) {
        return ApiResponse.ok(userInternalService.userNames(ids));
    }

    /**
     * 查询用户在某租户下的角色编码（内部接口：customer-service 判工单权限用）。
     *
     * <p>走内部接口而不是转发调用方的令牌：服务间调用不该依赖租户侧的登录态，
     * 也避免把用户令牌在多个服务之间传来传去。</p>
     */
    @GetMapping("/users/{userId}/roles")
    public ApiResponse<List<String>> userRoles(
            @PathVariable long userId,
            @RequestParam String tenantCode
    ) {
        return ApiResponse.ok(memberInviteService.roleCodesOf(tenantCode, userId));
    }
}
