package io.supportflow.customer.controller.session;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.CustomerInternalCredential.Caller;
import io.supportflow.customer.dto.quality.TimeoutScanResult;
import io.supportflow.customer.dto.session.ConnectionBody;
import io.supportflow.customer.dto.session.RoutingRunVO;
import io.supportflow.customer.security.CustomerInternalCall;
import io.supportflow.customer.security.InternalCustomerEndpoint;
import io.supportflow.customer.service.session.AgentStatusService;
import io.supportflow.customer.service.quality.RealtimeQaService;
import io.supportflow.customer.service.session.RoutingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * 内部接口：仅供 supportflow-realtime-service 调用（坐席上下线、触发一次排队调度）。
 */
@RestController
@InternalCustomerEndpoint(Caller.REALTIME)
@RequestMapping("/api/customer/internal/agent")
public class AgentInternalController {

    private final AgentStatusService agentStatusService;
    private final RoutingService routingService;
    private final RealtimeQaService realtimeQaService;

    public AgentInternalController(
            AgentStatusService agentStatusService,
            RoutingService routingService,
            RealtimeQaService realtimeQaService
    ) {
        this.agentStatusService = agentStatusService;
        this.routingService = routingService;
        this.realtimeQaService = realtimeQaService;
    }

    /**
     * 坐席长连接上线/下线：路由只把新会话分给"在线且连着"的人，所以这个标记必须实时更新。
     */
    @PostMapping("/connection")
    public ApiResponse<Void> connection(@RequestAttribute(CustomerInternalCall.ATTRIBUTE) CustomerInternalCall call,
                                        @Valid @RequestBody ConnectionBody body) {
        return call.inTenant(body.tenantCode(), () -> {
            agentStatusService.markConnected(body.tenantCode(), body.agentId(), body.connected());
            if (Boolean.TRUE.equals(body.connected())) {
                routingService.assignQueue(body.tenantCode());
            }
            return ApiResponse.ok(null);
        });
    }

    /**
     * 手动触发一次排队调度（排障用）。
     */
    @PostMapping("/routing/run")
    public ApiResponse<RoutingRunVO> runRouting(@RequestAttribute(CustomerInternalCall.ATTRIBUTE) CustomerInternalCall call,
                                               @RequestParam String tenantCode) {
        return call.inTenant(tenantCode, () -> ApiResponse.ok(new RoutingRunVO(routingService.assignQueue(tenantCode))));
    }

    /**
     * 手动触发一次"响应超时"扫描（排障用）。
     *
     * <p>想知道"为什么没提醒"就调它：返回本轮扫到多少条"客户在等"的会话、
     * 实际新增多少条提醒。scanned 一直是 0 说明扫描条件没命中（比如坐席最后说过话、
     * 或者会话不在接待/排队状态）；scanned 有值但 alerted 是 0，说明还没到超时阈值。</p>
     */
    @PostMapping("/qa-timeout/run")
    @InternalCustomerEndpoint(value = Caller.OPS, tenantScoped = false)
    public ApiResponse<TimeoutScanResult> runTimeoutScan() {
        return ApiResponse.ok(realtimeQaService.scanUnansweredSessions());
    }
}
