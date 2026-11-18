package io.supportflow.tenant.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.tenant.dto.QuotaBody;
import io.supportflow.tenant.dto.TenantDetailVO;
import io.supportflow.tenant.dto.TenantNameVO;
import io.supportflow.tenant.dto.TenantOverviewVO;
import io.supportflow.tenant.dto.TenantPageVO;
import io.supportflow.tenant.dto.TenantSecurityEventVO;
import io.supportflow.tenant.service.TenantIsolationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台侧「租户管理」接口（仅平台管理员 user_type=1）：
 * 隔离模式 / 库版本 / 限流配额 / 用量 / 升级历史 / 安全事件，以及冻结解冻、发起升级、调配额。
 */
@RestController
@RequestMapping("/api/tenant/isolation")
public class TenantIsolationController {

    private final TenantIsolationService service;

    public TenantIsolationController(TenantIsolationService service) {
        this.service = service;
    }

    /** 租户列表（筛选 + 分页，带配额与今日用量） */
    @GetMapping("/tenants")
    public ApiResponse<TenantPageVO> list(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer isolationMode,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        requirePlatform(LoginUserContext.require());
        return ApiResponse.ok(service.page(status, isolationMode, keyword,
                Math.max(1, page), Math.min(Math.max(1, pageSize), 100)));
    }

    /**
     * 租户目录：编码 → 企业名称（平台侧跨租户页面做映射用）。
     *
     * <p>为什么单独一个接口：收款流水这类页面只有 tenant_code，
     * 但订单表在 billing_db、企业名在 tenant_db，跨库 join 不了；
     * 页面拉一次目录做成 map 最省事（隔离列表分页每页最多 100 条，不适合当目录用）。</p>
     */
    @GetMapping("/tenant-names")
    public ApiResponse<List<TenantNameVO>> tenantNames() {
        requirePlatform(LoginUserContext.require());
        return ApiResponse.ok(service.tenantNames());
    }

    /** 平台概览：总数 / 活跃 / 独立库 / 冻结 / 落后版本 / 今日被限流 */
    @GetMapping("/overview")
    public ApiResponse<TenantOverviewVO> overview() {
        requirePlatform(LoginUserContext.require());
        return ApiResponse.ok(service.overview());
    }

    /** 租户详情：隔离与限流 + 升级历史 + 安全事件 */
    @GetMapping("/tenants/{tenantCode}")
    public ApiResponse<TenantDetailVO> detail(
            @PathVariable String tenantCode
    ) {
        requirePlatform(LoginUserContext.require());
        return ApiResponse.ok(service.detail(tenantCode));
    }

    /** 调整限流配额 */
    @PostMapping("/tenants/{tenantCode}/quota")
    public ApiResponse<TenantDetailVO> updateQuota(
            @PathVariable String tenantCode,
            @RequestBody QuotaBody body
    ) {
        LoginUser user = LoginUserContext.require();
        requirePlatform(user);
        return ApiResponse.ok(service.updateQuota(user, tenantCode,
                body == null ? new QuotaBody(null, null, null, null, null, null) : body));
    }

    /** 切换隔离模式（独立实例需要运维介入，接口直接拒绝） */
    @PostMapping("/tenants/{tenantCode}/isolation-mode")
    public ApiResponse<TenantDetailVO> switchMode(
            @PathVariable String tenantCode,
            @RequestParam int mode,
            @RequestParam(required = false) String schemaName
    ) {
        LoginUser user = LoginUserContext.require();
        requirePlatform(user);
        return ApiResponse.ok(service.switchIsolationMode(user, tenantCode, mode, schemaName));
    }

    /** 发起租户库升级 */
    @PostMapping("/tenants/{tenantCode}/upgrade")
    public ApiResponse<TenantDetailVO> upgrade(
            @PathVariable String tenantCode,
            @RequestParam(required = false) Integer toVersion
    ) {
        LoginUser user = LoginUserContext.require();
        requirePlatform(user);
        return ApiResponse.ok(service.upgrade(user, tenantCode, toVersion));
    }

    /** 冻结 / 解冻租户 */
    @PostMapping("/tenants/{tenantCode}/freeze")
    public ApiResponse<TenantDetailVO> freeze(
            @PathVariable String tenantCode,
            @RequestParam boolean freeze
    ) {
        LoginUser user = LoginUserContext.require();
        requirePlatform(user);
        return ApiResponse.ok(service.freeze(user, tenantCode, freeze));
    }

    /** 全平台最近的安全事件（隔离告警看板） */
    @GetMapping("/security-events")
    public ApiResponse<List<TenantSecurityEventVO>> securityEvents(
            @RequestParam(required = false) String tenantCode,
            @RequestParam(defaultValue = "20") int limit
    ) {
        requirePlatform(LoginUserContext.require());
        return ApiResponse.ok(service.securityEvents(tenantCode, Math.min(Math.max(1, limit), 200)));
    }

    private static void requirePlatform(LoginUser user) {
        if (user == null || user.userType() != 1) {
            throw new BizException(40302, "只有平台管理员能管理租户隔离与限流");
        }
    }
}
