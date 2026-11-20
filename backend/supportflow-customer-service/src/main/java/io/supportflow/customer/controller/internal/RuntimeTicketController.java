package io.supportflow.customer.controller.internal;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.security.RuntimeCustomerCall;
import io.supportflow.customer.security.RuntimeCustomerEndpoint;
import io.supportflow.customer.service.bot.AgentTicketQueries;
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
@RequestMapping("/api/customer/internal/runtime/events/{eventId}/runs/{runId}/tickets/{ticketNo}")
public class RuntimeTicketController {
    private final AgentTicketQueries queries;
    private final TransactionTemplate transaction;
    public RuntimeTicketController(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        queries=new AgentTicketQueries(jdbc); transaction=new TransactionTemplate(manager); transaction.setTimeout(10);
    }
    @GetMapping public ApiResponse<Map<String,Object>> read(@RequestAttribute(RuntimeCustomerCall.ATTRIBUTE) RuntimeCustomerCall call,
            @PathVariable UUID eventId,@PathVariable UUID runId,@PathVariable String ticketNo) {
        return call.inTenant(eventId,runId,()->ApiResponse.ok(wire(transaction.execute(status->queries.read(call.tenant(),eventId,runId,ticketNo)))));
    }
    private static Map<String,Object> wire(AgentTicketQueries.Status value) {
        return Map.ofEntries(Map.entry("schema_version","ticket-status-v1"),Map.entry("tenant_code",value.tenant()),
                Map.entry("source_event_id",value.eventId().toString()),Map.entry("run_id",value.runId().toString()),
                Map.entry("snapshot_id",value.snapshotId().toString()),Map.entry("source_hash",value.sourceHash()),
                Map.entry("ticket_no",value.ticketNo()),Map.entry("status",value.status()),
                Map.entry("updated_at",value.updatedAt()),Map.entry("resource_version",value.resourceVersion()));
    }
    @ExceptionHandler(BizException.class) public ResponseEntity<ApiResponse<Void>> rejected(BizException failure) {
        int status=switch(failure.getCode()) { case 40402,40404 -> 404; case 40902 -> 409; case 41002 -> 410;
            case 41302 -> 413; case 42202,42204 -> 422; default -> 403; };
        return ResponseEntity.status(status).body(ApiResponse.fail(failure.getCode(),failure.getMessage()));
    }
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<ApiResponse<Void>> unavailable() {
        return ResponseEntity.status(503).body(ApiResponse.fail(503,"Runtime persistence unavailable"));
    }
}
