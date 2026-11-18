package io.supportflow.tenant.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.tenant.dto.CustomerSuccessVO;
import io.supportflow.tenant.dto.TenantHealthVO;
import io.supportflow.tenant.service.CustomerSuccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台「客户成功」：租户健康度、续费风险、扩展商机。
 *
 * <p>这一半是"算出来的"（数据在 tenant_db）；"谁负责回访、什么时候回访"那一半在
 * ops-service 的 customer_success 表，页面按租户编号合并展示。</p>
 */
@RestController
@RequestMapping("/api/tenant/admin/customer-success")
public class PlatformCustomerSuccessController {

    private final CustomerSuccessService customerSuccessService;

    public PlatformCustomerSuccessController(CustomerSuccessService customerSuccessService) {
        this.customerSuccessService = customerSuccessService;
    }

    /** 健康度列表 + 概览（KPI 与列表同源，数字天然自洽） */
    @GetMapping
    public ApiResponse<CustomerSuccessVO> overview() {
        requirePlatform();
        return ApiResponse.ok(customerSuccessService.overview());
    }

    /** 单个租户的健康度分解 */
    @GetMapping("/{tenantCode}")
    public ApiResponse<TenantHealthVO> detail(
            @PathVariable String tenantCode
    ) {
        requirePlatform();
        return ApiResponse.ok(customerSuccessService.detail(tenantCode));
    }

    private void requirePlatform() {
        LoginUser user = LoginUserContext.require();
        if (user.userType() != 1) {
            throw new BizException(40302, "只有平台账号能看客户成功数据");
        }
    }
}
