package io.supportflow.customer.controller.session;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.context.TenantContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.web.RequestUtils;
import io.supportflow.customer.dto.session.CsatBody;
import io.supportflow.customer.dto.session.CsatResult;
import io.supportflow.customer.dto.session.EventVO;
import io.supportflow.customer.dto.session.MessageVO;
import io.supportflow.customer.dto.session.OpenBody;
import io.supportflow.customer.dto.session.OpenCommand;
import io.supportflow.customer.dto.session.OpenResult;
import io.supportflow.customer.dto.session.SessionVO;
import io.supportflow.customer.dto.session.VisitorPrincipal;
import io.supportflow.customer.dto.session.WorkloadVO;
import io.supportflow.customer.security.OpenSessionRateLimiter;
import io.supportflow.customer.security.ChannelTenantResolver;
import io.supportflow.customer.security.VisitorTokenService;
import io.supportflow.customer.service.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 在线客服会话接口：访客开会话 + 坐席工作台读取会话与聊天记录。
 */
@RestController
@RequestMapping("/api/customer/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final OpenSessionRateLimiter openSessionRateLimiter;
    private final VisitorTokenService visitorTokenService;
    private final ChannelTenantResolver channelTenantResolver;

    public SessionController(
            SessionService sessionService,
            OpenSessionRateLimiter openSessionRateLimiter,
            VisitorTokenService visitorTokenService,
            ChannelTenantResolver channelTenantResolver
    ) {
        this.sessionService = sessionService;
        this.openSessionRateLimiter = openSessionRateLimiter;
        this.visitorTokenService = visitorTokenService;
        this.channelTenantResolver = channelTenantResolver;
    }

    /**
     * 满意度评价：会话结束后，客户在访客页打分（坐席回访时代录也行）。
     *
     * <p>两种身份都能调：</p>
     * <ul>
     *   <li><b>访客</b>：带开会话时拿到的会话令牌（visitorToken），
     *       校验令牌里的租户与会话号必须和路径上的一致——不然谁都能给别人打差评；</li>
     *   <li><b>坐席</b>：带登录令牌，记在动态里的操作人就是这位坐席（"代录"要看得出来）。</li>
     * </ul>
     */
    @PostMapping("/{sessionNo}/csat")
    public ApiResponse<CsatResult> submitCsat(
            @PathVariable String sessionNo,
            @RequestBody CsatBody body
    ) {
        int score = body.score() == null ? 0 : body.score();
        if (LoginUserContext.tokenPresent()) {
            LoginUser user = LoginUserContext.require();
            return ApiResponse.ok(sessionService.submitCsat(user.tenantCode(), sessionNo, score,
                    body.feedback(), user.userId(), user.name()));
        }
        VisitorPrincipal principal = visitorTokenService.parseToken(body.visitorToken());
        if (principal == null || !sessionNo.equals(principal.sessionNo())) {
            throw new BizException(40301, "访客身份无效，请重新打开客服窗口后再评价");
        }
        return TenantContext.bind(principal.tenantCode(), () -> ApiResponse.ok(
                sessionService.submitCsat(principal.tenantCode(), sessionNo, score,
                        body.feedback(), null, principal.customerName())));
    }

    /**
     * 访客打开会话：前端用渠道密钥调用，无需登录，返回会话令牌 + 访客身份令牌。
     *
     * <p>来源域名（Origin / Referer）用于渠道白名单校验；客户端 IP 用于开会话限流。</p>
     */
    @PostMapping("/open")
    public ApiResponse<OpenResult> open(
            @Valid @RequestBody OpenBody body,
            HttpServletRequest request
    ) {
        openSessionRateLimiter.check(body.appKey(), RequestUtils.clientIp(request));
        String tenant = channelTenantResolver.resolve(body.appKey());
        return TenantContext.bind(tenant, () -> ApiResponse.ok(sessionService.openSession(new OpenCommand(
                body.appKey(),
                body.visitorToken(),
                body.visitorName(),
                RequestUtils.origin(request)))));
    }

    /**
     * 坐席工作台：会话列表。scope 取值：all-全部、queue-待接待、mine-我的会话。
     */
    @GetMapping
    public ApiResponse<List<SessionVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "all") String scope
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(sessionService.agentSessions(user, scope, keyword));
    }

    /**
     * 工作台顶部计数：我的接待 / 待接待（服务端按固定口径算，不受当前标签页影响）。
     */
    @GetMapping("/summary")
    public ApiResponse<WorkloadVO> summary() {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(sessionService.workload(user));
    }

    /**
     * 会话详情。
     */
    @GetMapping("/{sessionNo}")
    public ApiResponse<SessionVO> detail(
            @PathVariable String sessionNo
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(sessionService.sessionDetail(user, sessionNo));
    }

    /**
     * 聊天记录（坐席翻页用，beforeId 传上一页最小消息 ID）。
     */
    @GetMapping("/{sessionNo}/messages")
    public ApiResponse<List<MessageVO>> messages(
            @PathVariable String sessionNo,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Long afterSeq,
            @RequestParam(required = false) Integer limit
    ) {
        LoginUser user = LoginUserContext.require();
        if (afterSeq != null) {
            // 断线重连补偿：只要序号大于 afterSeq 的那几条
            return ApiResponse.ok(sessionService.messagesAfter(user, sessionNo, afterSeq, limit));
        }
        return ApiResponse.ok(sessionService.history(user, sessionNo, beforeId, limit));
    }

    /**
     * 会话流转记录（转接 / 分配 / 关闭）。
     */
    @GetMapping("/{sessionNo}/events")
    public ApiResponse<List<EventVO>> events(
            @PathVariable String sessionNo
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(sessionService.events(user, sessionNo));
    }
}
