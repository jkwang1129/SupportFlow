package io.supportflow.customer.controller.ticket;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.context.TenantContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.internal.UserRoleClient;
import io.supportflow.customer.service.bot.AgentApprovalReview;
import io.supportflow.customer.service.bot.AgentRuntimeClient;
import io.supportflow.customer.service.bot.AgentSourceSnapshots;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Reviews a known proposal using authenticated staff identity and current customer permissions. */
@RestController
@RequestMapping("/api/customer/agent-approvals")
public class AgentTicketApprovalController {
    private static final int BODY_LIMIT = 2048;
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final JdbcTemplate jdbc;
    private final UserRoleClient roles;
    private final AgentSourceSnapshots snapshots;
    private final TransactionTemplate transaction;
    private final AgentRuntimeClient runtime;

    @Autowired
    public AgentTicketApprovalController(JdbcTemplate jdbc, PlatformTransactionManager manager, UserRoleClient roles,
            @Value("${supportflow.bot.runtime-approval-review-enabled:false}") boolean enabled,
            @Value("${supportflow.ai.bot-base-url:http://127.0.0.1:9100}") String ai,
            @Value("${SUPPORTFLOW_AI_CUSTOMER_SECRET:}") String secret) {
        this(jdbc, manager, roles, enabled ? new AgentRuntimeClient(
                URI.create(ai.replaceAll("/+$", "") + "/api/ai/v1/runtime/runs"), secret, Duration.ofSeconds(3)) : null);
    }

    public AgentTicketApprovalController(JdbcTemplate jdbc, PlatformTransactionManager manager, UserRoleClient roles, AgentRuntimeClient runtime) {
        this.jdbc = jdbc;
        this.roles = roles;
        this.runtime = runtime;
        snapshots = new AgentSourceSnapshots(jdbc);
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(5);
    }

    @PostMapping(path = "/events/{eventId}/runs/{runId}/decisions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, String>> decide(@PathVariable UUID eventId, @PathVariable UUID runId, HttpServletRequest request) {
        var user = requireReviewer();
        String tenant = user.tenantCode();
        var review = command(tenant, eventId, runId, user.userId(), readBody(request));
        return TenantContext.bind(tenant, () -> {
            transaction.executeWithoutResult(status -> verifySource(review));
            String decision = runtime.reviewTicketApproval(review);
            return ApiResponse.ok(Map.of("approval_id", review.approvalId().toString(), "status", decision));
        });
    }

    @GetMapping("/events/{eventId}/runs/{runId}/proposal")
    public ApiResponse<Map<String, String>> proposal(@PathVariable UUID eventId, @PathVariable UUID runId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        String tenant = requireReviewer().tenantCode();
        return TenantContext.bind(tenant, () -> {
            var source = transaction.execute(status -> snapshots.read(tenant, eventId, runId));
            var proposal = runtime.previewTicketApproval(source);
            transaction.executeWithoutResult(status -> snapshots.read(tenant, eventId, runId));
            return ApiResponse.ok(proposal.response());
        });
    }

    @GetMapping("/events/{eventId}/runs/{runId}/progress")
    public ApiResponse<Map<String, Object>> progress(@PathVariable UUID eventId, @PathVariable UUID runId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        String tenant = requireReviewer().tenantCode();
        return TenantContext.bind(tenant, () -> {
            var source = transaction.execute(status -> snapshots.read(tenant, eventId, runId));
            var progress = runtime.approvalProgress(source);
            transaction.executeWithoutResult(status -> snapshots.read(tenant, eventId, runId));
            return ApiResponse.ok(progressView(progress));
        });
    }

    @GetMapping("/events/{eventId}/runs/{runId}/timeline")
    public ApiResponse<Map<String, Object>> timeline(@PathVariable UUID eventId, @PathVariable UUID runId,
            @RequestParam(name="afterSeq", defaultValue="0") int afterSeq, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        String tenant = requireReviewer().tenantCode();
        if (afterSeq < 0) throw new BizException(42207, "事件序号无效");
        return TenantContext.bind(tenant, () -> {
            var source = transaction.execute(status -> snapshots.read(tenant, eventId, runId));
            var page = runtime.approvalEvents(source, afterSeq);
            var current = transaction.execute(status -> snapshots.read(tenant, eventId, runId));
            if (!source.snapshotId().equals(current.snapshotId())) throw new BizException(40407, "审批任务不可用");
            return ApiResponse.ok(page);
        });
    }

    @GetMapping("/sessions/{sessionNo}/active")
    public ApiResponse<Map<String, String>> active(@PathVariable String sessionNo, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        String tenant = requireReviewer().tenantCode();
        return TenantContext.bind(tenant, () -> {
            requireReviewableSession(tenant, sessionNo);
            var reference = runtime.activeTicketApproval(tenant, sessionNo);
            if (reference == null) return ApiResponse.ok();
            transaction.executeWithoutResult(status -> sessionSource(tenant, sessionNo, reference.eventId(), reference.runId()));
            return ApiResponse.ok(Map.of("source_event_id", reference.eventId().toString(), "run_id", reference.runId().toString(),
                    "status", reference.status(), "expires_at", reference.expiresAt().toString()));
        });
    }

    @GetMapping("/sessions/{sessionNo}/latest-progress")
    public ApiResponse<Map<String, Object>> latestProgress(@PathVariable String sessionNo,
            @RequestParam(name="includeTimeline", defaultValue="false") boolean includeTimeline, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        String tenant = requireReviewer().tenantCode();
        return TenantContext.bind(tenant, () -> {
            requireReviewableSession(tenant, sessionNo);
            var approved = runtime.latestReviewedApproval(tenant, sessionNo);
            if (approved == null) return ApiResponse.ok();
            AgentSourceSnapshots.Snapshot source;
            try {
                source = transaction.execute(status -> sessionSource(tenant, sessionNo, approved.eventId(), approved.runId()));
            } catch (BizException unavailable) {
                throw new BizException(40407, "审批任务不可用");
            }
            if (source == null || !approved.snapshotId().equals(source.snapshotId()))
                throw new BizException(40407, "审批任务不可用");
            var progress = progressView(approved.progress());
            if (!includeTimeline) return ApiResponse.ok(progress);
            var details = new java.util.LinkedHashMap<>(progress);
            details.put("timeline", Map.of("source_event_id", approved.eventId().toString(), "run_id", approved.runId().toString()));
            return ApiResponse.ok(details);
        });
    }

    private void requireReviewableSession(String tenant, String sessionNo) {
        if (!sessionNo.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")) throw new BizException(42207, "会话编号无效");
        Boolean eligible = transaction.execute(status -> jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM session WHERE tenant_code=? AND session_no=?
                    AND is_deleted=false AND agent_id IS NULL AND status IN (1,2))
                """, Boolean.class, tenant, sessionNo));
        if (!Boolean.TRUE.equals(eligible)) throw new BizException(40407, "可用的审批会话不存在");
    }

    private AgentSourceSnapshots.Snapshot sessionSource(String tenant, String sessionNo, UUID eventId, UUID runId) {
        Boolean delivered = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM agent_event_outbox WHERE tenant_code=? AND session_no=?
                    AND event_id=? AND run_id=? AND state='DELIVERED')
                """, Boolean.class, tenant, sessionNo, eventId, runId);
        if (!Boolean.TRUE.equals(delivered)) throw new BizException(40407, "审批来源不可用");
        return snapshots.read(tenant, eventId, runId);
    }

    private static Map<String, Object> progressView(AgentRuntimeClient.RunProgress progress) {
        return Map.of("state", progress.state(), "attempt", progress.attempt(), "step_count", progress.stepCount());
    }

    private LoginUser requireReviewer() {
        var user = LoginUserContext.require();
        if (runtime == null) throw new BizException(50307, "审批服务未启用");
        String tenant = user.tenantCode();
        if (user.userType() != 2 || user.userId() < 1 || tenant == null
                || !tenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}") || tenant.equalsIgnoreCase("PLATFORM")) {
            throw new BizException(40307, "无审批权限");
        }
        var currentRoles = roles.rolesOf(user.userId(), tenant);
        if (currentRoles == null || !(currentRoles.contains("ADMIN") || currentRoles.contains("SUPERVISOR"))) {
            throw new BizException(40307, "无审批权限");
        }
        return user;
    }

    private JsonNode readBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > BODY_LIMIT) throw new BizException(41307, "审批请求过大");
        try {
            byte[] raw = request.getInputStream().readNBytes(BODY_LIMIT + 1);
            if (raw.length > BODY_LIMIT) throw new BizException(41307, "审批请求过大");
            return mapper.readTree(raw);
        } catch (IOException invalid) {
            throw new BizException(42207, "审批请求无效");
        }
    }

    private void verifySource(AgentApprovalReview review) {
        if (review.decision() == AgentApprovalReview.Decision.APPROVE) {
            snapshots.read(review.tenant(), review.eventId(), review.runId());
        } else {
            // Narrowing decisions stay available after a session takeover.
            var found = jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM agent_event_outbox WHERE tenant_code=? AND event_id=? AND run_id=? AND state='DELIVERED')
                    """, Boolean.class, review.tenant(), review.eventId(), review.runId());
            if (!Boolean.TRUE.equals(found)) throw new BizException(40407, "审批来源不可用");
        }
    }

    private static AgentApprovalReview command(String tenant, UUID event, UUID run, long reviewer, JsonNode body) {
        try {
            if (body == null || !body.isObject() || body.size() != 3) throw new IllegalArgumentException();
            String raw = text(body, "approval_id");
            UUID approval = UUID.fromString(raw);
            if (!approval.toString().equals(raw)) throw new IllegalArgumentException();
            var decision = switch (text(body, "decision")) {
                case "approve" -> AgentApprovalReview.Decision.APPROVE;
                case "deny" -> AgentApprovalReview.Decision.DENY;
                case "revoke" -> AgentApprovalReview.Decision.REVOKE;
                default -> throw new IllegalArgumentException();
            };
            return new AgentApprovalReview(tenant, run, approval, event, text(body, "arguments_sha256"), reviewer, decision);
        } catch (IllegalArgumentException invalid) {
            throw new BizException(42207, "审批请求无效");
        }
    }

    private static String text(JsonNode body, String name) {
        var value = body.get(name);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException();
        return value.textValue();
    }

    @ExceptionHandler(AgentRuntimeClient.DeliveryFailure.class)
    public ResponseEntity<ApiResponse<Void>> reviewFailure(AgentRuntimeClient.DeliveryFailure failure) {
        return switch (failure.code()) {
            case "APPROVAL_REVIEW_HTTP_409", "APPROVAL_PREVIEW_HTTP_409", "APPROVAL_QUEUE_HTTP_409" ->
                    ResponseEntity.status(409).body(ApiResponse.fail(40907, "审批请求已变化或过期"));
            case "APPROVAL_PREVIEW_HTTP_404" -> ResponseEntity.status(404).body(ApiResponse.fail(40407, "审批提案不可用"));
            case "APPROVAL_PROGRESS_HTTP_404" -> ResponseEntity.status(404).body(ApiResponse.fail(40407, "审批任务不可用"));
            case "APPROVAL_EVENTS_HTTP_404" -> ResponseEntity.status(404).body(ApiResponse.fail(40407, "审批任务不可用"));
            case "APPROVAL_EVENTS_HTTP_409" -> ResponseEntity.status(409).body(ApiResponse.fail(40907, "事件序号超出当前进度"));
            default -> unavailable();
        };
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable() {
        return ResponseEntity.status(503).body(ApiResponse.fail(50307, "审批服务暂不可用"));
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> rejected(BizException failure) {
        int code = failure.getCode();
        int status = code >= 40000 && code < 60000 ? code / 100 : code >= 400 && code < 600 ? code : 500;
        return ResponseEntity.status(status).body(ApiResponse.fail(code, failure.getMessage()));
    }

    @PreDestroy
    public void close() { if (runtime != null) runtime.close(); }
}
