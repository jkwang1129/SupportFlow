package io.supportflow.customer.controller.session;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.customer.dto.session.AgentLoadVO;
import io.supportflow.customer.dto.session.AgentStatusUpdateBody;
import io.supportflow.customer.dto.session.AgentStatusVO;
import io.supportflow.customer.dto.session.StatusView;
import io.supportflow.customer.entity.AgentStatus;
import io.supportflow.customer.service.session.AgentStatusService;
import io.supportflow.customer.service.session.SessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 坐席状态：工作台右上角切「在线 / 忙碌 / 小休」，路由据此决定谁能接新会话。
 */
@RestController
@RequestMapping("/api/customer/agent-status")
public class AgentStatusController {

    private final AgentStatusService agentStatusService;
    private final SessionService sessionService;

    public AgentStatusController(
            AgentStatusService agentStatusService,
            SessionService sessionService
    ) {
        this.agentStatusService = agentStatusService;
        this.sessionService = sessionService;
    }

    /**
     * 我的状态 + 同事状态（工作台一次性拿全）。
     */
    @GetMapping
    public ApiResponse<StatusView> list() {
        LoginUser user = LoginUserContext.require();
        AgentStatus mine = agentStatusService.of(user);
        List<AgentStatus> all = agentStatusService.list(mine.getTenantCode());
        // 当前接待量：路由派单时就是按"已接待单数 < 上限"筛人的。
        // 工作台把它显示出来，坐席才能明白"我明明在线，为什么没派给我"（接满了）。
        Map<Long, Integer> workload = sessionService.agentWorkload(mine.getTenantCode()).stream()
                .collect(Collectors.toMap(AgentLoadVO::agentId,
                        load -> load.sessionCount() == null ? 0 : load.sessionCount(), (left, right) -> left));
        return ApiResponse.ok(new StatusView(
                toVO(mine, workload),
                all.stream().map(item -> toVO(item, workload)).toList()));
    }

    /**
     * 改我的状态（可选同时改"最多同时接待几单"）。
     */
    @PutMapping
    public ApiResponse<AgentStatusVO> update(
            @Valid @RequestBody AgentStatusUpdateBody body
    ) {
        LoginUser user = LoginUserContext.require();
        AgentStatus updated = agentStatusService.update(user, body.status(), body.maxConcurrency());
        Map<Long, Integer> workload = sessionService.agentWorkload(updated.getTenantCode()).stream()
                .collect(Collectors.toMap(AgentLoadVO::agentId,
                        load -> load.sessionCount() == null ? 0 : load.sessionCount(), (left, right) -> left));
        return ApiResponse.ok(toVO(updated, workload));
    }

    private AgentStatusVO toVO(AgentStatus status, Map<Long, Integer> workload) {
        int code = status.getStatus() == null ? AgentStatusService.STATUS_ONLINE : status.getStatus();
        int max = status.getMaxConcurrency() == null ? 5 : status.getMaxConcurrency();
        int active = workload.getOrDefault(status.getAgentId(), 0);
        return new AgentStatusVO(
                String.valueOf(status.getAgentId()),
                code,
                statusText(code),
                max,
                active,
                Boolean.TRUE.equals(status.getIsConnected()));
    }

    private String statusText(int status) {
        return switch (status) {
            case AgentStatusService.STATUS_BUSY -> "忙碌";
            case AgentStatusService.STATUS_BREAK -> "小休";
            default -> "在线";
        };
    }
}
