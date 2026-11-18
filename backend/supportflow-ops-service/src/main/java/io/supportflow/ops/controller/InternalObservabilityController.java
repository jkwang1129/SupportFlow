package io.supportflow.ops.controller;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.ops.dto.CostGuardVO;
import io.supportflow.ops.service.ObservabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可观测对内的接口：**成本闸门**。
 *
 * <p>customer-service 在每次调 AI 之前问一次"这个租户还能不能花"：
 * 返回 {@code mode = 1 正常 / 2 降级到规则与检索 / 3 暂停 AI}。
 * 这就是「成本预算 + 超限动作」的执行点——**没有这一问，超限动作只是一个配置项**。</p>
 *
 * <p>结果在 ops 侧缓存 60 秒（业务侧每个请求都问一次太贵），
 * 拿不到答案时一律按"正常"放行：成本治理不该变成"运维服务抖一下就全站 AI 不能用"。</p>
 */
@RestController
@RequestMapping("/api/ops/internal/observability")
public class InternalObservabilityController {

    private final ObservabilityService service;

    public InternalObservabilityController(ObservabilityService service) {
        this.service = service;
    }

    /** 成本闸门：AI 侧每次调用前问一次（正常 / 降级 / 暂停）。 */
    @GetMapping("/cost-guard")
    public ApiResponse<CostGuardVO> costGuard(@RequestParam(required = false) String tenantCode) {
        return ApiResponse.ok(service.costGuard(tenantCode));
    }
}
