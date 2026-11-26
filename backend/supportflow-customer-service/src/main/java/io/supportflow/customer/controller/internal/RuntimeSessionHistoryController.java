package io.supportflow.customer.controller.internal;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.security.RuntimeCustomerCall;
import io.supportflow.customer.security.RuntimeCustomerEndpoint;
import io.supportflow.customer.service.bot.AgentSessionHistoryQueries;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RuntimeCustomerEndpoint
@RequestMapping("/api/customer/internal/runtime/events/{eventId}/runs/{runId}/session-history")
public class RuntimeSessionHistoryController {
    private final AgentSessionHistoryQueries queries;
    private final TransactionTemplate transaction;

    public RuntimeSessionHistoryController(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.queries = new AgentSessionHistoryQueries(jdbc);
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(10);
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> read(
            @RequestAttribute(RuntimeCustomerCall.ATTRIBUTE) RuntimeCustomerCall call,
            @PathVariable UUID eventId,
            @PathVariable UUID runId) {
        return call.inTenant(eventId, runId, () -> ApiResponse.ok(
                wire(transaction.execute(status -> queries.read(call.tenant(), eventId, runId)))));
    }

    private static Map<String, Object> wire(AgentSessionHistoryQueries.History value) {
        List<Map<String, Object>> sessions = value.sessions().stream().map(session -> {
            List<Map<String, Object>> messages = session.messages().stream().map(msg -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("seq", msg.seq());
                item.put("sender_type", msg.senderType());
                item.put("content", msg.content());
                item.put("sent_at", msg.sentAt());
                return Collections.unmodifiableMap(item);
            }).toList();

            Map<String, Object> sessionMap = new LinkedHashMap<>();
            sessionMap.put("session_no", session.sessionNo());
            sessionMap.put("status", session.status());
            sessionMap.put("started_at", session.startedAt());
            sessionMap.put("ended_at", session.endedAt());
            sessionMap.put("messages", messages);
            return Collections.unmodifiableMap(sessionMap);
        }).toList();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", "session-history-v1");
        root.put("tenant_code", value.tenant());
        root.put("source_event_id", value.eventId().toString());
        root.put("run_id", value.runId().toString());
        root.put("snapshot_id", value.snapshotId().toString());
        root.put("source_hash", value.sourceHash());
        root.put("bound", value.bound());
        root.put("sessions", sessions);
        root.put("resource_version", value.resourceVersion());
        return Collections.unmodifiableMap(root);
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> rejected(BizException failure) {
        int status = switch (failure.getCode()) {
            case 40402, 40408 -> 404;
            case 40902 -> 409;
            case 41002 -> 410;
            case 41302 -> 413;
            case 42202, 42208 -> 422;
            default -> 403;
        };
        return ResponseEntity.status(status).body(ApiResponse.fail(failure.getCode(), failure.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable() {
        return ResponseEntity.status(503).body(ApiResponse.fail(503, "Runtime persistence unavailable"));
    }
}
