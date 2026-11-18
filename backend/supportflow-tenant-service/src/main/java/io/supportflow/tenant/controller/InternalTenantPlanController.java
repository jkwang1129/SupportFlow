package io.supportflow.tenant.controller;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.tenant.dto.ActiveTenantVO;
import io.supportflow.tenant.dto.AppliedPlanVO;
import io.supportflow.tenant.dto.PlanBody;
import io.supportflow.tenant.dto.TenantPlanVO;
import io.supportflow.tenant.service.TenantPlanService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内部接口：billing-service 支付成功后调用，把套餐与到期时间写回租户，并同步限流配额。
 *
 * <p>控制器只做"接参数 + 包响应"，租户校验、到期计算、配额同步都在
 * {@link TenantPlanService} 里——这些是业务规则，不该散在接口层。</p>
 */
@RestController
@RequestMapping("/api/tenant/internal/tenants")
public class InternalTenantPlanController {

    private final TenantPlanService tenantPlanService;

    public InternalTenantPlanController(TenantPlanService tenantPlanService) {
        this.tenantPlanService = tenantPlanService;
    }

    /**
     * 查租户当前套餐（只读）：billing 算用量与账单时的权威来源。
     */
    @GetMapping("/{tenantCode}/plan")
    public ApiResponse<TenantPlanVO> currentPlan(@PathVariable String tenantCode) {
        return ApiResponse.ok(tenantPlanService.currentPlan(tenantCode));
    }

    /**
     * 活跃租户列表（已开通 + 有套餐）：billing 的月度出账任务按它逐个生成账单。
     */
    @GetMapping("/active")
    public ApiResponse<List<ActiveTenantVO>> activeTenants() {
        return ApiResponse.ok(tenantPlanService.activeTenants());
    }

    /**
     * 全部租户编码（状态正常、未删除）——平台公告要推送给所有租户的通知中心。
     */
    @GetMapping("/codes")
    public ApiResponse<List<String>> tenantCodes() {
        return ApiResponse.ok(tenantPlanService.tenantCodes());
    }

    /** 更新租户套餐 + 到期时间 + 限流配额 */
    @PostMapping("/{tenantCode}/plan")
    public ApiResponse<AppliedPlanVO> applyPlan(
            @PathVariable String tenantCode,
            @RequestBody PlanBody body
    ) {
        return ApiResponse.ok(tenantPlanService.applyPlan(tenantCode, body));
    }
}
