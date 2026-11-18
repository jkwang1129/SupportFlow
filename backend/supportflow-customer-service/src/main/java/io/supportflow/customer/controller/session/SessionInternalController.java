package io.supportflow.customer.controller.session;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.customer.dto.session.AgentBody;
import io.supportflow.customer.dto.session.AgentLoadVO;
import io.supportflow.customer.dto.session.AppendBody;
import io.supportflow.customer.dto.session.CloseBody;
import io.supportflow.customer.dto.session.MessageVO;
import io.supportflow.customer.dto.session.SessionInfo;
import io.supportflow.customer.dto.session.TransferBody;
import io.supportflow.customer.entity.Session;
import io.supportflow.customer.service.session.SessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 内部接口：仅供 supportflow-realtime-service 调用（长连接里的会话与消息落库）。
 *
 * <p>实时网关不直接连客户库，避免"两个服务同时写一个库"，
 * 这条边界在生产可以平滑替换成 RPC 或消息队列。</p>
 */
@RestController
@RequestMapping("/api/customer/internal/sessions")
public class SessionInternalController {

    private final SessionService sessionService;

    public SessionInternalController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 取会话（实时网关鉴权后确认会话存在、状态可用）。
     */
    @GetMapping("/{sessionNo}")
    public ApiResponse<SessionInfo> detail(
            @PathVariable String sessionNo,
            @RequestParam String tenantCode
    ) {
        return ApiResponse.ok(toInfo(sessionService.requireSession(tenantCode, sessionNo)));
    }

    /**
     * 拉历史消息（JOIN 时回给前端）。
     */
    @GetMapping("/{sessionNo}/messages")
    public ApiResponse<List<MessageVO>> messages(
            @PathVariable String sessionNo,
            @RequestParam String tenantCode,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Long afterSeq,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "true") boolean agentView
    ) {
        if (afterSeq != null) {
            // 增量补拉：重连后只要断线期间漏掉的那几条
            return ApiResponse.ok(sessionService.messagesAfter(tenantCode, sessionNo, afterSeq, limit, agentView));
        }
        return ApiResponse.ok(sessionService.historyByTenant(tenantCode, sessionNo, beforeId, limit, agentView));
    }

    /**
     * 消息落库。
     */
    @PostMapping("/{sessionNo}/messages")
    public ApiResponse<MessageVO> append(
            @PathVariable String sessionNo,
            @Valid @RequestBody AppendBody body
    ) {
        return ApiResponse.ok(sessionService.appendMessage(
                body.tenantCode(),
                sessionNo,
                body.senderType() == null ? SessionService.SENDER_CUSTOMER : body.senderType(),
                body.senderId(),
                body.msgType() == null ? 1 : body.msgType(),
                body.content(),
                body.visibleTo() == null ? SessionService.VISIBLE_ALL : body.visibleTo(),
                body.clientMsgNo()
        ));
    }

    /**
     * 坐席认领会话。
     */
    @PostMapping("/{sessionNo}/claim")
    public ApiResponse<SessionInfo> claim(
            @PathVariable String sessionNo,
            @Valid @RequestBody AgentBody body
    ) {
        return ApiResponse.ok(toInfo(sessionService.claimSession(body.tenantCode(), sessionNo, body.agentId())));
    }

    /**
     * 退回队列。
     */
    @PostMapping("/{sessionNo}/release")
    public ApiResponse<SessionInfo> release(
            @PathVariable String sessionNo,
            @Valid @RequestBody AgentBody body
    ) {
        return ApiResponse.ok(toInfo(sessionService.releaseSession(body.tenantCode(), sessionNo, body.agentId())));
    }

    /**
     * 转接给其他坐席。
     */
    @PostMapping("/{sessionNo}/transfer")
    public ApiResponse<SessionInfo> transfer(
            @PathVariable String sessionNo,
            @Valid @RequestBody TransferBody body
    ) {
        return ApiResponse.ok(toInfo(sessionService.transferSession(
                body.tenantCode(), sessionNo, body.fromAgentId(), body.toAgentId(), body.remark())));
    }

    /**
     * 结束会话（可带结束小结）。
     */
    @PostMapping("/{sessionNo}/close")
    public ApiResponse<SessionInfo> close(
            @PathVariable String sessionNo,
            @Valid @RequestBody CloseBody body
    ) {
        return ApiResponse.ok(toInfo(sessionService.closeSession(
                body.tenantCode(), sessionNo, body.operatorId(), body.remark())));
    }

    /**
     * 各坐席当前接待量（工作台展示谁忙谁闲）。
     */
    @GetMapping("/workload")
    public ApiResponse<List<AgentLoadVO>> workload(@RequestParam String tenantCode) {
        return ApiResponse.ok(sessionService.agentWorkload(tenantCode));
    }

    private SessionInfo toInfo(Session session) {
        return new SessionInfo(
                session.getSessionNo(),
                session.getTenantCode(),
                session.getId(),
                session.getStatus(),
                session.getAgentId(),
                session.getCustomerId(),
                session.getStartTime(),
                session.getEndTime(),
                session.getLastMsgSeq()
        );
    }
}
