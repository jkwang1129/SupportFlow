package io.supportflow.tenant.controller;

import io.supportflow.common.api.AcceptedCountVO;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.tenant.dto.SecurityReport;
import io.supportflow.tenant.dto.TenantQuotaVO;
import io.supportflow.tenant.dto.UsageReport;
import io.supportflow.tenant.service.TenantIsolationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内部接口：只给网关用（网关是限流与租户头校验的执行点）。
 * 1. 取限额：网关缓存 30 秒，避免每个请求都打到 tenant-service；
 * 2. 上报用量 / 告警：网关每 5 秒批量上报，不在请求链路上写库。
 */
@RestController
@RequestMapping("/api/tenant/internal/isolation")
public class InternalTenantIsolationController {

    private final TenantIsolationService service;

    public InternalTenantIsolationController(TenantIsolationService service) {
        this.service = service;
    }

    /** 网关取某个租户的限额与状态 */
    @GetMapping("/quota/{tenantCode}")
    public ApiResponse<TenantQuotaVO> quota(@PathVariable String tenantCode) {
        return ApiResponse.ok(service.quotaForGateway(tenantCode));
    }

    /** 网关批量上报用量 */
    @PostMapping("/usage/report")
    public ApiResponse<AcceptedCountVO> reportUsage(
            @RequestBody List<UsageReport> reports
    ) {
        service.reportUsage(reports);
        return ApiResponse.ok(new AcceptedCountVO(reports == null ? 0 : reports.size()));
    }

    /** 网关上报告警（篡改租户头 / 跨租户访问被拒 / 被限流） */
    @PostMapping("/security-event")
    public ApiResponse<Void> reportSecurityEvent(
            @RequestBody SecurityReport report
    ) {
        service.reportSecurityEvent(report);
        return ApiResponse.ok();
    }
}
