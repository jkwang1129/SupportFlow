package io.supportflow.customer.controller.internal;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.security.*;
import io.supportflow.customer.service.bot.*;
import io.supportflow.customer.service.session.SessionService;
import io.supportflow.customer.service.ticket.TicketService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Explicitly enabled business bridge; GET confirms a past commit without new execution authority. */
@RestController
@RuntimeCustomerEndpoint
@RequestMapping("/api/customer/internal/runtime/events/{eventId}/runs/{runId}/ticket-create")
public class RuntimeTicketCreateController {
    private final AgentTicketCreations creations;
    private final TransactionTemplate transaction;
    private final AgentRuntimeClient authority;
    private final String noticeTenant;
    @Autowired public RuntimeTicketCreateController(JdbcTemplate jdbc,PlatformTransactionManager manager,TicketService tickets,
            SessionService sessions, ApplicationEventPublisher events,
            @Value("${supportflow.bot.runtime-ticket-notice-tenant:}") String noticeTenant,
            @Value("${supportflow.bot.runtime-ticket-create-enabled:false}") boolean enabled,
            @Value("${supportflow.ai.bot-base-url:http://127.0.0.1:9100}") String ai,
            @Value("${SUPPORTFLOW_AI_CUSTOMER_SECRET:}") String secret){
        this(jdbc,manager,tickets,sessions,events,noticeTenant,enabled ? new AgentRuntimeClient(
                URI.create(ai.replaceAll("/+$","")+"/api/ai/v1/runtime/runs"),secret,Duration.ofSeconds(3)) : null);
    }
    public RuntimeTicketCreateController(JdbcTemplate jdbc,PlatformTransactionManager manager,TicketService tickets,AgentRuntimeClient authority){
        this(jdbc,manager,tickets,null,null,"",authority);
    }
    private RuntimeTicketCreateController(JdbcTemplate jdbc,PlatformTransactionManager manager,TicketService tickets,
                                          SessionService sessions,ApplicationEventPublisher events,String noticeTenant,AgentRuntimeClient authority){
        if (!noticeTenant.isEmpty() && (!noticeTenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}")
                || noticeTenant.equalsIgnoreCase("PLATFORM"))) throw new IllegalArgumentException("Invalid ticket notice tenant");
        this.noticeTenant=noticeTenant;
        creations=new AgentTicketCreations(jdbc,tickets,sessions,events);transaction=new TransactionTemplate(manager);transaction.setTimeout(5);this.authority=authority;
    }
    @PostMapping public ApiResponse<Map<String,String>> create(@RequestAttribute(RuntimeCustomerCall.ATTRIBUTE) RuntimeCustomerCall call,
            @RequestAttribute(RuntimeTicketCreateCommand.ATTRIBUTE) RuntimeTicketCreateCommand command,@PathVariable UUID eventId,@PathVariable UUID runId){
        if(authority==null)throw new BizException(50306,"Runtime ticket creation is disabled");
        return call.inTenant(eventId,runId,()->{
            var args=command.ticket();
            if(!args.tenant().equals(call.tenant()) || !args.eventId().equals(eventId) || !args.runId().equals(runId)
                    || command.action().operation()!=AgentActionPermit.Operation.TICKET_CREATE)throw new BizException(40306,"任务资源与服务授权不一致");
            var existing=transaction.execute(status->creations.find(args));
            if(existing!=null)return ApiResponse.ok(wire(call.tenant(),existing));
            // No business connection or row lock may remain held during this bounded RPC.
            var permit=authority.authorizeTicketCreate(command.action(),noticeTenant.equals(call.tenant()));
            return ApiResponse.ok(wire(call.tenant(),transaction.execute(status->creations.create(permit,args))));
        });
    }
    @GetMapping public ApiResponse<Map<String,String>> get(@RequestAttribute(RuntimeCustomerCall.ATTRIBUTE) RuntimeCustomerCall call,
            @PathVariable UUID eventId,@PathVariable UUID runId){
        return call.inTenant(eventId,runId,()->{
            var result=transaction.execute(status->creations.get(call.tenant(),eventId,runId));
            if(result==null)throw new BizException(40406,"建单回执不存在");
            return ApiResponse.ok(wire(call.tenant(),result));
        });
    }
    private static Map<String,String> wire(String tenant,AgentTicketCreations.Receipt result){
        return Map.of("tenant_code",tenant,"source_event_id",result.eventId().toString(),"run_id",result.runId().toString(),
            "snapshot_id",result.snapshotId().toString(),"schema_version",AgentTicketArguments.VERSION,"operation","ticket.create",
            "arguments_sha256",result.argumentsSha256(),"ticket_id",Long.toString(result.ticketId()),"ticket_no",result.ticketNo());
    }
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<ApiResponse<Void>> persistenceFailure(){
        return ResponseEntity.status(503).body(ApiResponse.fail(503,"Runtime persistence unavailable"));
    }
    @ExceptionHandler(AgentRuntimeClient.DeliveryFailure.class) public ResponseEntity<ApiResponse<Void>> authorityFailure(AgentRuntimeClient.DeliveryFailure failure){
        int status=switch(failure.code()){case "AUTHORITY_HTTP_404"->404;case "AUTHORITY_HTTP_409"->409;default->503;};
        return ResponseEntity.status(status).body(ApiResponse.fail(status,"Runtime action authority unavailable"));
    }
    @ExceptionHandler(BizException.class) public ResponseEntity<ApiResponse<Void>> businessFailure(BizException failure){
        int status=failure.getCode()/100;if(status<400 || status>599)status=500;
        return ResponseEntity.status(status).body(ApiResponse.fail(failure.getCode(),failure.getMessage()));
    }
    @PreDestroy public void close(){if(authority!=null)authority.close();}
}
