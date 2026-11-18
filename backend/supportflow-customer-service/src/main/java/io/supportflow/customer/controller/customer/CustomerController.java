package io.supportflow.customer.controller.customer;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.api.ServicePing;
import io.supportflow.common.context.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * customer-service 骨架接口。
 */
@RestController
@RequestMapping("/api/customer")
public class CustomerController {

    /**
     * 健康探针：确认服务起来了、租户上下文能透传。
     */
    @GetMapping("/ping")
    public ApiResponse<ServicePing> ping() {
        return ApiResponse.ok(new ServicePing("customer-service",
                TenantContext.get() == null ? "" : TenantContext.get()));
    }
}
