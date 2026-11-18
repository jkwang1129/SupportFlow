package io.supportflow.ops.controller;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.api.ServicePing;
import io.supportflow.common.context.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ops-service 骨架接口。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    /** 健康探针：确认服务起来了、租户上下文能透传。 */
    @GetMapping("/ping")
    public ApiResponse<ServicePing> ping() {
        return ApiResponse.ok(new ServicePing("ops-service",
                TenantContext.get() == null ? "" : TenantContext.get()));
    }
}
