package io.supportflow.realtime.controller;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.exception.BizException;
import io.supportflow.realtime.dto.AssignedBody;
import io.supportflow.realtime.dto.ClaimedBody;
import io.supportflow.realtime.dto.DeliveredResult;
import io.supportflow.realtime.dto.NotifyMessageBody;
import io.supportflow.realtime.dto.PresenceView;
import io.supportflow.realtime.dto.QaAlertBody;
import io.supportflow.realtime.dto.RealtimeHealthVO;
import io.supportflow.realtime.dto.TicketAlertBody;
import io.supportflow.realtime.dto.TypingBody;
import io.supportflow.realtime.security.RealtimePrincipal;
import io.supportflow.realtime.security.RealtimeTokenParser;
import io.supportflow.realtime.ws.ConnectionRegistry;
import io.supportflow.realtime.ws.RealtimeWebSocketHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 在线状态查询：坐席工作台定期（或切回前台时）用 HTTP 对一次账。
 *
 * <p>为什么不用长连接？因为"状态过期"的典型原因恰恰是长连接断了、或被后台标签页节流，
 * 这时候再让客户端通过长连接要数据是自相矛盾的。HTTP 走网关，链路独立，才能自愈。</p>
 */
@RestController
@RequestMapping("/api/realtime")
public class PresenceController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ConnectionRegistry registry;
    private final RealtimeTokenParser tokenParser;
    private final RealtimeWebSocketHandler handler;

    public PresenceController(
            ConnectionRegistry registry,
            RealtimeTokenParser tokenParser,
            RealtimeWebSocketHandler handler
    ) {
        this.registry = registry;
        this.tokenParser = tokenParser;
        this.handler = handler;
    }

    /**
     * 当前租户在线的访客会话号列表（只认坐席令牌）。
     */
    @GetMapping("/presence")
    public ApiResponse<PresenceView> presence(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        RealtimePrincipal principal = tokenParser.parse(resolveBearer(authorization));
        if (principal.isVisitor()) {
            // 访客令牌只能进自己那一条会话，不能拿来看整个租户的在线情况
            throw new BizException(40301, "访客无权查看坐席在线状态");
        }
        return ApiResponse.ok(new PresenceView(
                List.copyOf(registry.onlineVisitorSessions(principal.tenantCode()))));
    }

    private String resolveBearer(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }

    /**
     * 内部接口：customer-service 智能路由分配成功后调用，通知坐席"你被派单了"。
     */
    @PostMapping("/internal/assigned")
    public ApiResponse<DeliveredResult> assigned(@RequestBody AssignedBody body) {
        int delivered = handler.notifyAssigned(
                body.tenantCode(), body.agentId(), body.sessionNo(), body.reason());
        return ApiResponse.ok(new DeliveredResult(delivered));
    }

    /**
     * 内部接口：会话被自动分配（智能路由 / 机器人转人工）后，把状态同步给会话里的所有人。
     *
     * <p>坐席手点「接入会话」走长连接、天然会广播；自动分配发生在 customer-service，
     * 不通知一次的话，客户会一直停在"正在为您转接人工客服，请稍候"。</p>
     */
    @PostMapping("/internal/claimed")
    public ApiResponse<DeliveredResult> claimed(@RequestBody ClaimedBody body) {
        int delivered = handler.notifyClaimed(body.tenantCode(), body.sessionNo(), body.agentId());
        return ApiResponse.ok(new DeliveredResult(delivered));
    }

    /**
     * 内部接口：customer-service 实时质检命中后调用，把预警推给这条会话的坐席。
     */
    @PostMapping("/internal/qa-alert")
    public ApiResponse<DeliveredResult> qaAlert(@RequestBody QaAlertBody body) {
        int delivered = handler.notifyQaAlert(
                body.tenantCode(), body.sessionNo(), body.agentId(), body.payload());
        return ApiResponse.ok(new DeliveredResult(delivered));
    }

    /**
     * 内部接口：工单提醒（分派 / SLA 即将超时 / 已超时）。
     *
     * <p>和质检预警不同，工单没有"会话订阅关系"，所以按**人**投递：
     * 有处理人发给处理人，没人认领就发给全租户坐席。</p>
     */
    @PostMapping("/internal/ticket-alert")
    public ApiResponse<DeliveredResult> ticketAlert(@RequestBody TicketAlertBody body) {
        int delivered = handler.notifyTicketAlert(body.tenantCode(), body.assigneeId(), body.payload());
        return ApiResponse.ok(new DeliveredResult(delivered));
    }

    /**
     * 内部接口：customer-service 落了一条"机器人回复 / 系统提示"后调用，让长连接广播出去。
     *
     * <p>机器人回复不是从前端长连接发起的，长连接手里没有这条消息；
     * 不推一次，访客就只能靠刷新页面才看到机器人回了什么。</p>
     */
    @PostMapping("/internal/message")
    public ApiResponse<DeliveredResult> message(@RequestBody NotifyMessageBody body) {
        int delivered = handler.notifyMessage(
                body.tenantCode(), body.sessionNo(), body.message(),
                body.refreshAgents() == null || body.refreshAgents());
        return ApiResponse.ok(new DeliveredResult(delivered));
    }

    /**
     * 内部接口：customer-service 通知"机器人/坐席正在输入"，由长连接推给双方。
     *
     * <p>客户发完消息到机器人答上来有几秒，中间没有任何反馈，客户会怀疑消息没发出去。</p>
     */
    @PostMapping("/internal/typing")
    public ApiResponse<DeliveredResult> typing(@RequestBody TypingBody body) {
        int delivered = handler.notifyTyping(
                body.tenantCode(), body.sessionNo(), body.who(), body.typing() == null || body.typing());
        return ApiResponse.ok(new DeliveredResult(delivered));
    }

    /**
     * 内部接口：探活 + 版本自证。
     *
     * <p>customer-service 靠它判断"对面这个实时网关是不是新版"——
     * 旧版没有 /internal/message，机器人回复推不出去，现象却是"机器人不回话"，
     * 查起来很绕。这里把版本能力直接写在返回里。</p>
     */
    @GetMapping("/internal/health")
    public ApiResponse<RealtimeHealthVO> health() {
        // internalMessage 这一项是"版本指纹"：旧版没有 /internal/message，机器人回复推不出去
        return ApiResponse.ok(new RealtimeHealthVO("UP", true, registry.totalConnections()));
    }
}
