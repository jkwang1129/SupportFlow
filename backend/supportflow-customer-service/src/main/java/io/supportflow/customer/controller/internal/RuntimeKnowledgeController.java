package io.supportflow.customer.controller.internal;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.security.RuntimeCustomerCall;
import io.supportflow.customer.security.RuntimeCustomerEndpoint;
import io.supportflow.customer.security.RuntimeKnowledgeCommand;
import io.supportflow.customer.service.bot.AgentKnowledgeQueries;
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
@RequestMapping("/api/customer/internal/runtime/events/{eventId}/runs/{runId}/knowledge/search")
public class RuntimeKnowledgeController {
    private final AgentKnowledgeQueries queries;
    private final TransactionTemplate transaction;
    public RuntimeKnowledgeController(JdbcTemplate jdbc,PlatformTransactionManager manager){queries=new AgentKnowledgeQueries(jdbc);transaction=new TransactionTemplate(manager);transaction.setTimeout(5);}
    @PostMapping public ApiResponse<Map<String,Object>> read(@RequestAttribute(RuntimeCustomerCall.ATTRIBUTE)RuntimeCustomerCall call,
            @RequestAttribute(RuntimeKnowledgeCommand.ATTRIBUTE)RuntimeKnowledgeCommand command,@PathVariable UUID eventId,@PathVariable UUID runId){
        return call.inTenant(eventId,runId,()->ApiResponse.ok(wire(transaction.execute(status->queries.read(call.tenant(),eventId,runId,command)))));
    }
    private static Map<String,Object> wire(AgentKnowledgeQueries.Result value){
        var hits=value.hits().stream().map(hit->Map.<String,Object>of("doc_id",hit.docId(),"chunk_id",hit.chunkId(),"title",hit.title(),
                "snippet",hit.snippet(),"score_ppm",hit.scorePpm(),"embedding_model",hit.embeddingModel())).toList();
        return Map.ofEntries(Map.entry("schema_version","knowledge-search-v1"),Map.entry("policy_version",AgentKnowledgeQueries.POLICY),
                Map.entry("tenant_code",value.tenant()),Map.entry("source_event_id",value.eventId().toString()),Map.entry("run_id",value.runId().toString()),
                Map.entry("snapshot_id",value.snapshotId().toString()),Map.entry("source_hash",value.sourceHash()),Map.entry("request_sha256",value.requestHash()),
                Map.entry("query_sha256",value.queryHash()),Map.entry("embedding_model",value.embeddingModel()),Map.entry("hits",hits),Map.entry("resource_version",value.resourceVersion()));
    }
    @ExceptionHandler(BizException.class) public ResponseEntity<ApiResponse<Void>> rejected(BizException failure){
        int status=switch(failure.getCode()){case 40402->404;case 40902,40905->409;case 41002->410;case 41302->413;case 42202,42205->422;default->403;};
        return ResponseEntity.status(status).body(ApiResponse.fail(failure.getCode(),failure.getMessage()));
    }
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<ApiResponse<Void>> unavailable(){
        return ResponseEntity.status(503).body(ApiResponse.fail(503,"Runtime persistence unavailable"));
    }
}
