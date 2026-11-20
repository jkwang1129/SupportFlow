package io.supportflow.customer.controller.internal;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.customer.security.RuntimeCustomerCall;
import io.supportflow.customer.security.RuntimeCustomerEndpoint;
import io.supportflow.customer.service.bot.AgentSourceSnapshots;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RuntimeCustomerEndpoint
@RequestMapping("/api/customer/internal/runtime/events/{eventId}/runs/{runId}/snapshot")
public class RuntimeSourceController {
    private final AgentSourceSnapshots snapshots;
    private final TransactionTemplate transaction;
    public RuntimeSourceController(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        snapshots = new AgentSourceSnapshots(jdbc); transaction = new TransactionTemplate(manager); transaction.setTimeout(10);
    }
    @PostMapping public ApiResponse<Map<String, String>> capture(@RequestAttribute(RuntimeCustomerCall.ATTRIBUTE) RuntimeCustomerCall call,
                                                               @PathVariable UUID eventId, @PathVariable UUID runId) {
        return call.inTenant(eventId, runId, () -> ApiResponse.ok(wire(transaction.execute(status -> snapshots.capture(call.tenant(), eventId, runId)))));
    }
    @GetMapping public ApiResponse<Map<String, String>> read(@RequestAttribute(RuntimeCustomerCall.ATTRIBUTE) RuntimeCustomerCall call,
                                                            @PathVariable UUID eventId, @PathVariable UUID runId) {
        return call.inTenant(eventId, runId, () -> ApiResponse.ok(wire(transaction.execute(status -> snapshots.read(call.tenant(), eventId, runId)))));
    }
    private static Map<String, String> wire(AgentSourceSnapshots.Snapshot snapshot) {
        return Map.of("tenant_code", snapshot.tenant(), "source_event_id", snapshot.eventId().toString(), "run_id", snapshot.runId().toString(),
                "snapshot_id", snapshot.snapshotId().toString(), "schema_version", snapshot.schemaVersion(), "payload_json", snapshot.payloadJson(),
                "payload_hash", snapshot.payloadHash(), "created_at", snapshot.createdAt().toString(), "expires_at", snapshot.expiresAt().toString());
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable() {
        return ResponseEntity.status(503).body(ApiResponse.fail(503, "Runtime persistence unavailable"));
    }
}
