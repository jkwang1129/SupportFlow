package io.supportflow.tenant.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.tenant.dto.HandleBody;
import io.supportflow.tenant.dto.SecurityEventHandleVO;
import io.supportflow.tenant.dto.SecurityEventPageVO;
import io.supportflow.tenant.service.SecurityEventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台「安全中心 → 安全事件」：跨租户的事件列表 + 处置。
 *
 * <p>事件数据在 tenant_db（探测侧写入），所以接口放在 tenant-service；
 * 安全策略 / 密钥 / 导出审批在 ops-service，页面把两边合并展示。</p>
 */
@RestController
@RequestMapping("/api/tenant/admin/security-events")
public class PlatformSecurityEventController {

    private final SecurityEventService securityEventService;

    public PlatformSecurityEventController(SecurityEventService securityEventService) {
        this.securityEventService = securityEventService;
    }

    /** 安全事件列表：可按类型 / 处置状态 / 租户筛，带概览与近 7 天趋势。 */
    @GetMapping
    public ApiResponse<SecurityEventPageVO> events(
            @RequestParam(required = false) Integer eventType,
            @RequestParam(required = false) Integer handleStatus,
            @RequestParam(required = false) String tenantCode,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize
    ) {
        requirePlatform();
        return ApiResponse.ok(securityEventService.events(eventType, handleStatus, tenantCode, page, pageSize));
    }

    /** 处置安全事件：2-已确认（真实越界）、3-已忽略（误报）。 */
    @PostMapping("/{id}/handle")
    public ApiResponse<SecurityEventHandleVO> handle(
            @PathVariable long id,
            @RequestBody HandleBody body
    ) {
        LoginUser operator = requirePlatform();
        int status = body == null || body.status() == null ? 2 : body.status();
        return ApiResponse.ok(securityEventService.handle(operator, id, status,
                body == null ? null : body.remark()));
    }

    private LoginUser requirePlatform() {
        LoginUser user = LoginUserContext.require();
        if (user.userType() != 1) {
            throw new BizException(40302, "只有平台账号能看安全事件");
        }
        return user;
    }
}
