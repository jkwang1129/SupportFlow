package io.supportflow.customer.controller.internal;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.security.RuntimeCustomerCall;
import io.supportflow.customer.security.RuntimeCustomerEndpoint;
import io.supportflow.customer.security.RuntimeReplyCommand;
import io.supportflow.customer.service.bot.AgentResultReplies;
import io.supportflow.customer.service.bot.AgentRuntimeClient;
import io.supportflow.customer.service.bot.AgentSourceSnapshots;
import io.supportflow.customer.service.session.SessionService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Explicit result bridge. A service credential alone never permits a new write. */
@RestController
@RuntimeCustomerEndpoint
@RequestMapping("/api/customer/internal/runtime/events/{eventId}/runs/{runId}/reply")
public class RuntimeReplyController {

    private final AgentResultReplies replies;
    private final TransactionTemplate transaction;
    private final AgentRuntimeClient authority;

    @Autowired
    public RuntimeReplyController(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            SessionService sessions,
            @Value("${supportflow.bot.runtime-result-enabled:false}") boolean enabled,
            @Value("${supportflow.ai.bot-base-url:http://127.0.0.1:9100}") String ai,
            @Value("${SUPPORTFLOW_AI_CUSTOMER_SECRET:}") String secret
    ) {
        this(
                jdbc,
                manager,
                sessions,
                enabled
                        ? new AgentRuntimeClient(
                                URI.create(ai.replaceAll("/+$", "") + "/api/ai/v1/runtime/runs"),
                                secret,
                                Duration.ofSeconds(3))
                        : null
        );
    }

    public RuntimeReplyController(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            SessionService sessions,
            AgentRuntimeClient authority
    ) {
        this.replies = new AgentResultReplies(jdbc, new AgentSourceSnapshots(jdbc), sessions);
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(5);
        this.authority = authority;
    }

    private void ensureEnabled() {
        if (authority == null) {
            throw new BizException(50303, "Runtime result API is disabled");
        }
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> append(
            @RequestAttribute(RuntimeCustomerCall.ATTRIBUTE) RuntimeCustomerCall call,
            @RequestAttribute(RuntimeReplyCommand.ATTRIBUTE) RuntimeReplyCommand command,
            @PathVariable UUID eventId,
            @PathVariable UUID runId
    ) {
        ensureEnabled();
        return call.inTenant(eventId, runId, () -> {
            if (!command.reply().tenant().equals(call.tenant())
                    || !command.reply().eventId().equals(eventId)
                    || !command.reply().runId().equals(runId)) {
                throw new BizException(40303, "任务资源与服务授权不一致");
            }
            var existing = transaction.execute(status -> replies.find(command.reply()));
            if (existing != null) {
                return ApiResponse.ok(wire(call.tenant(), existing));
            }
            // This RPC must occur after the preflight transaction committed and
            // before the result transaction obtains any JDBC connection/row lock.
            var permit = authority.authorizeReply(command.action());
            return ApiResponse.ok(wire(call.tenant(), transaction.execute(status -> replies.append(permit, command.reply()))));
        });
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> get(
            @RequestAttribute(RuntimeCustomerCall.ATTRIBUTE) RuntimeCustomerCall call,
            @PathVariable UUID eventId,
            @PathVariable UUID runId
    ) {
        ensureEnabled();
        return call.inTenant(eventId, runId, () -> {
            var result = transaction.execute(status -> replies.get(call.tenant(), eventId, runId));
            if (result == null) {
                throw new BizException(40403, "任务结果不存在");
            }
            return ApiResponse.ok(wire(call.tenant(), result));
        });
    }

    private static Map<String, Object> wire(String tenant, AgentResultReplies.Receipt result) {
        return Map.of(
                "tenant_code", tenant,
                "source_event_id", result.eventId().toString(),
                "run_id", result.runId().toString(),
                "snapshot_id", result.snapshotId().toString(),
                "schema_version", "reply-v1",
                "operation", "session.reply",
                "arguments_sha256", result.argumentsSha256(),
                "message_id", Long.toString(result.messageId()),
                "message_no", result.messageNo(),
                "seq", Long.toString(result.sequence())
        );
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> persistenceFailure() {
        return ResponseEntity.status(503).body(ApiResponse.fail(503, "Runtime persistence unavailable"));
    }

    @ExceptionHandler(AgentRuntimeClient.DeliveryFailure.class)
    public ResponseEntity<ApiResponse<Void>> authorityFailure(AgentRuntimeClient.DeliveryFailure failure) {
        int status = switch (failure.code()) {
            case "AUTHORITY_HTTP_404" -> 404;
            case "AUTHORITY_HTTP_409" -> 409;
            default -> 503;
        };
        return ResponseEntity.status(status).body(ApiResponse.fail(status, "Runtime action authority unavailable"));
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> businessFailure(BizException failure) {
        int status = failure.getCode() / 100;
        if (status < 400 || status > 599) {
            status = 500;
        }
        return ResponseEntity.status(status).body(ApiResponse.fail(failure.getCode(), failure.getMessage()));
    }

    @PreDestroy
    public void close() {
        if (authority != null) {
            authority.close();
        }
    }
}
