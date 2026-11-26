package io.supportflow.customer.controller.internal;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.security.RuntimeCustomerCall;
import io.supportflow.customer.security.RuntimeCustomerEndpoint;
import io.supportflow.customer.service.bot.AgentCustomerQueries;
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
import java.util.Map;
import java.util.UUID;

@RestController
@RuntimeCustomerEndpoint
@RequestMapping("/api/customer/internal/runtime/events/{eventId}/runs/{runId}/customer-profile")
public class RuntimeCustomerProfileController {
    private final AgentCustomerQueries queries;
    private final TransactionTemplate transaction;

    public RuntimeCustomerProfileController(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.queries = new AgentCustomerQueries(jdbc);
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

    private static Map<String, Object> wire(AgentCustomerQueries.Profile value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schema_version", "customer-profile-v1");
        map.put("tenant_code", value.tenant());
        map.put("source_event_id", value.eventId().toString());
        map.put("run_id", value.runId().toString());
        map.put("snapshot_id", value.snapshotId().toString());
        map.put("source_hash", value.sourceHash());
        map.put("bound", value.bound());
        map.put("customer_no", value.customerNo());
        map.put("masked_name", value.maskedName());
        map.put("masked_phone", value.maskedPhone());
        map.put("level", value.level());
        map.put("customer_type", value.customerType());
        map.put("points", value.points());
        map.put("orders_count", value.ordersCount());
        map.put("ticket_count", value.ticketCount());
        map.put("session_count", value.sessionCount());
        map.put("risk_level", value.riskLevel());
        map.put("resource_version", value.resourceVersion());
        return Collections.unmodifiableMap(map);
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> rejected(BizException failure) {
        int status = switch (failure.getCode()) {
            case 40402, 40407 -> 404;
            case 40902 -> 409;
            case 41002 -> 410;
            case 41302 -> 413;
            case 42202, 42207 -> 422;
            default -> 403;
        };
        return ResponseEntity.status(status).body(ApiResponse.fail(failure.getCode(), failure.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable() {
        return ResponseEntity.status(503).body(ApiResponse.fail(503, "Runtime persistence unavailable"));
    }
}
