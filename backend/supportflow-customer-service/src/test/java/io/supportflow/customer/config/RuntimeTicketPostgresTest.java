package io.supportflow.customer.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportflow.common.auth.CustomerInternalCredential;
import io.supportflow.common.exception.GlobalExceptionHandler;
import io.supportflow.common.readiness.SchemaReadiness;
import io.supportflow.common.readiness.SchemaReadinessFilter;
import io.supportflow.customer.controller.internal.RuntimeTicketController;
import io.supportflow.customer.security.CustomerInternalAccess;
import io.supportflow.customer.security.RuntimeSourceAccessFilter;
import io.supportflow.customer.service.bot.AgentEventOutbox;
import io.supportflow.customer.service.bot.AgentTicketQueries;
import io.supportflow.customer.testkit.RuntimeSourceWire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.net.URI;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

@EnabledIfEnvironmentVariable(named="SUPPORTFLOW_RLS_TEST_URL",matches=".+")
class RuntimeTicketPostgresTest extends SessionPostgresFixture {
    private static final ObjectMapper JSON=new ObjectMapper();
    private AgentEventOutbox.Event event;
    private UUID run;
    private String path,ticketNo;
    private long ticketId,customerId;
    private MockMvc mvc;
    private <T>T tx(Supplier<T> action){return scoped(()->transaction.execute(status->action.get()));}
    @BeforeEach void prepareTicketHttp() {
        var outbox=new AgentEventOutbox(jdbc);run=UUID.randomUUID();ticketId=IDS.incrementAndGet();ticketNo="RTK_"+ticketId;customerId=IDS.incrementAndGet();
        event=tx(()->{
            var message=service.appendMessage("TENANT_A",sessionNo,1,null,1,"synthetic source",1,"ticket-query-source");
            var value=outbox.enqueue("TENANT_A",Long.parseLong(message.msgId()),"runtime-v1",500);
            var lease=outbox.claim("TENANT_A","ticket-api-test",30);outbox.acknowledge(lease,value.eventId(),run);
            jdbc.update("INSERT INTO ticket(id,tenant_code,ticket_no,title,\"desc\",customer_name,session_no,source_session_id,status,update_time) VALUES(?,'TENANT_A',?,'PRIVATE-TITLE','PRIVATE-DESC','PRIVATE-NAME',?,?,2,TIMESTAMP '2026-11-20 12:34:00')",ticketId,ticketNo,sessionNo,sessionId);
            return value;
        });
        path="/api/customer/internal/runtime/events/"+event.eventId()+"/runs/"+run+"/tickets/"+ticketNo;
        var registry=new InterceptorRegistry(){HandlerInterceptor[] values(){return getInterceptors().stream().map(HandlerInterceptor.class::cast).toArray(HandlerInterceptor[]::new);}};
        new CustomerInternalAccess("","","").addInterceptors(registry);
        var readiness=new SchemaReadiness(jdbc.getDataSource(),Map.of("agent_source_snapshot","tenant_code,event_id,payload_hash,payload_json","ticket","tenant_code,ticket_no,status,customer_id"));
        mvc=MockMvcBuilders.standaloneSetup(new RuntimeTicketController(jdbc,transaction.getTransactionManager()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RuntimeSourceAccessFilter(RuntimeSourceWire.KEY,false,false,true,"","",""),new SchemaReadinessFilter(readiness))
                .addInterceptors(registry.values()).build();
    }
    private MvcResult get(String target,String tenant)throws Exception{
        return mvc.perform(request(HttpMethod.GET,URI.create(target)).header("X-Tenant-Code",tenant).header(CustomerInternalCredential.HEADER,
                RuntimeSourceWire.credential("GET",target,tenant,new byte[0],Instant.now().getEpochSecond()+20))).andReturn();
    }
    private JsonNode body(MvcResult result)throws Exception{return JSON.readTree(result.getResponse().getContentAsString());}
    private JsonNode success()throws Exception{var result=get(path,"TENANT_A");assertEquals(200,result.getResponse().getStatus());assertEquals(0,body(result).path("code").intValue());return body(result).get("data");}
    private void admin(String sql){try(var db=DriverManager.getConnection(System.getenv("SUPPORTFLOW_RLS_TEST_URL"),"supportflow",System.getenv("SUPPORTFLOW_RLS_TEST_MIGRATION_PASSWORD"));var statement=db.createStatement()){statement.execute(sql);}catch(Exception failure){throw new IllegalStateException("Disposable fixture administration failed",failure);}}
    private void customer(){tx(()->{
        jdbc.update("INSERT INTO customer(id,tenant_code,customer_no,name,phone) VALUES(?,'TENANT_A',?,'PRIVATE-CUSTOMER','PRIVATE-PHONE')",customerId,"RC_"+customerId);
        jdbc.update("UPDATE session SET customer_id=? WHERE id=?",customerId,sessionId);
        jdbc.update("UPDATE ticket SET customer_id=? WHERE id=?",customerId,ticketId);return null;
    });}
    private void denied(int status,int code)throws Exception{var result=get(path,"TENANT_A");assertEquals(status,result.getResponse().getStatus());assertEquals(code,body(result).path("code").intValue());assertTrue(body(result).path("data").isNull());assertFalse(result.getResponse().getContentAsString().contains("PRIVATE"));}
    @Override @AfterEach void cleanup(){try{admin("DELETE FROM ticket WHERE id="+ticketId+"; DELETE FROM agent_source_snapshot WHERE event_id IN(SELECT event_id FROM agent_event_outbox WHERE session_id="+sessionId+"); DELETE FROM agent_event_outbox WHERE session_id="+sessionId+"; DELETE FROM customer WHERE id="+customerId);}finally{super.cleanup();}}

    @Test void anonymousReadIsLimitedToExactSourceSessionAndReturnsOnlyTenFields()throws Exception{
        var data=success();assertEquals(10,data.size());assertEquals("ticket-status-v1",data.path("schema_version").textValue());
        assertEquals(ticketNo,data.path("ticket_no").textValue());assertEquals(2,data.path("status").intValue());assertTrue(data.path("status").isIntegralNumber());
        assertEquals("2026-11-20T12:34:00",data.path("updated_at").textValue());assertEquals(event.eventId().toString(),data.path("source_event_id").textValue());
        assertEquals(run.toString(),data.path("run_id").textValue());assertEquals(data,success());assertFalse(data.toString().contains("PRIVATE"));
        var canonical=String.join("\n","supportflow-ticket-status:v1","TENANT_A",event.eventId().toString(),run.toString(),data.path("snapshot_id").textValue(),data.path("source_hash").textValue(),ticketNo,"2","2026-11-20T12:34:00");
        assertEquals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))),data.path("resource_version").textValue());
        admin("UPDATE ticket SET source_session_id="+(sessionId+1)+" WHERE id="+ticketId);denied(404,40404);
        admin("UPDATE ticket SET source_session_id="+sessionId+",session_no='OTHER' WHERE id="+ticketId);denied(404,40404);
        admin("UPDATE ticket SET session_no='"+sessionNo+"',customer_id=99999999 WHERE id="+ticketId);denied(404,40404);
    }
    @Test void identifiedCustomerMayReadOwnHistoryWithoutEnterpriseMemberIdentity()throws Exception{
        customer();admin("UPDATE ticket SET source_session_id=NULL,session_no='HISTORICAL' WHERE id="+ticketId);success();
        admin("UPDATE ticket SET customer_id="+(customerId+1)+" WHERE id="+ticketId);denied(404,40404);
    }
    @Test void deletedAnonymizedOrMergedCustomerDoesNotAuthorizeHistory()throws Exception{
        customer();success();
        for(String fault: new String[]{"is_deleted=true","anonymized_at=CURRENT_TIMESTAMP","merged_into='OTHER'"}){
            admin("UPDATE customer SET "+fault+" WHERE id="+customerId);denied(404,40404);
            admin("UPDATE customer SET is_deleted=false,anonymized_at=NULL,merged_into=NULL WHERE id="+customerId);
        }
    }
    @Test void foreignTenantPlatformDeletedAndMissingTicketsHaveNoBody()throws Exception{
        success();admin("UPDATE ticket SET tenant_code='TENANT_B' WHERE id="+ticketId);denied(404,40404);
        var foreign=get(path,"TENANT_B");assertEquals(404,foreign.getResponse().getStatus());assertEquals(40402,body(foreign).path("code").intValue());
        admin("UPDATE ticket SET tenant_code='TENANT_A',ticket_type=2 WHERE id="+ticketId);denied(404,40404);
        admin("UPDATE ticket SET ticket_type=1,is_deleted=true WHERE id="+ticketId);denied(404,40404);
        var missing=get(path.replace(ticketNo,"NO-SUCH-TICKET"),"TENANT_A");assertEquals(40404,body(missing).path("code").intValue());
    }
    @Test void currentSourceTakeoverClosureAndDeletionAreRecheckedOnEveryRead()throws Exception{
        success();
        for(String fault:new String[]{"agent_id=12345","status=4","is_deleted=true"}){
            admin("UPDATE session SET "+fault+" WHERE id="+sessionId);denied(404,40402);
            admin("UPDATE session SET agent_id=NULL,status=2,is_deleted=false WHERE id="+sessionId);
        }
    }
    @Test void changedAndExpiredSourceSnapshotsAreNotSilentFreshPermissions()throws Exception{
        success();admin("UPDATE session_message SET content='changed' WHERE id="+event.messageId());denied(409,40902);
        admin("UPDATE session_message SET content='synthetic source' WHERE id="+event.messageId()+"; UPDATE agent_source_snapshot SET created_at=CURRENT_TIMESTAMP-interval '2 hours',expires_at=CURRENT_TIMESTAMP-interval '1 hour' WHERE event_id='"+event.eventId()+"'");denied(410,41002);
    }
    @Test void actualStateChangesProduceDifferentVersionEvenAtSameTimestamp()throws Exception{
        var before=success();admin("UPDATE ticket SET status=4 WHERE id="+ticketId);var after=success();
        assertEquals(4,after.path("status").intValue());assertNotEquals(before.path("resource_version"),after.path("resource_version"));
    }
    @Test void concurrentQueriesCreateOneSnapshotAndDoNotWriteBusinessMessages()throws Exception{
        try(var pool=Executors.newFixedThreadPool(6)){
            var futures=new ArrayList<java.util.concurrent.Future<JsonNode>>();for(int i=0;i<6;i++)futures.add(pool.submit(this::success));
            var first=futures.getFirst().get(10,TimeUnit.SECONDS);for(var future:futures)assertEquals(first,future.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,tx(()->jdbc.queryForObject("SELECT count(*) FROM agent_source_snapshot WHERE event_id=?",Integer.class,event.eventId())));
        assertEquals(1,tx(()->jdbc.queryForObject("SELECT count(*) FROM session_message WHERE session_id=?",Integer.class,sessionId)));
        assertEquals(0,tx(()->jdbc.queryForObject("SELECT count(*) FROM agent_result_receipt WHERE event_id=?",Integer.class,event.eventId())));
    }
    @Test void missingReadinessIsMaskedButAuthenticationStillRunsFirst()throws Exception{
        admin("ALTER TABLE ticket RENAME TO hidden_runtime_ticket");try{
            assertEquals(401,mvc.perform(request(HttpMethod.GET,URI.create(path))).andReturn().getResponse().getStatus());
            var blocked=get(path,"TENANT_A");assertEquals(503,blocked.getResponse().getStatus());assertFalse(blocked.getResponse().getContentAsString().contains("ticket"));
        }finally{admin("ALTER TABLE hidden_runtime_ticket RENAME TO ticket");}success();
    }
    @Test void privilegeErrorsAre503WithoutSqlAndRecoverWithoutChangingBusinessData()throws Exception{
        success();admin("REVOKE SELECT ON ticket FROM supportflow_customer_tenant");try{
            var blocked=get(path,"TENANT_A");assertEquals(503,blocked.getResponse().getStatus());assertFalse(blocked.getResponse().getContentAsString().contains("SELECT"));assertFalse(blocked.getResponse().getContentAsString().contains("ticket"));
        }finally{admin("GRANT SELECT ON ticket TO supportflow_customer_tenant");}success();
    }
    @Test void queriesRequireRealTenantScopeAndTransaction() {
        var queries=new AgentTicketQueries(jdbc);
        assertThrows(IllegalStateException.class,()->queries.read("TENANT_A",event.eventId(),run,ticketNo));
        assertThrows(IllegalStateException.class,()->transaction.execute(s->queries.read("TENANT_A",event.eventId(),run,ticketNo)));
    }
    @Test void customerAndTicketAuthorizationFactsRemainLockedUntilTransactionCompletes() {
        customer();tx(()->{
            new AgentTicketQueries(jdbc).read("TENANT_A",event.eventId(),run,ticketNo);
            try(var db=DriverManager.getConnection(System.getenv("SUPPORTFLOW_RLS_TEST_URL"),"supportflow",System.getenv("SUPPORTFLOW_RLS_TEST_MIGRATION_PASSWORD"));var statement=db.createStatement()){
                for(String query:new String[]{"SELECT id FROM ticket WHERE id="+ticketId+" FOR UPDATE NOWAIT","SELECT id FROM customer WHERE id="+customerId+" FOR UPDATE NOWAIT"}){
                    var denied=assertThrows(java.sql.SQLException.class,()->statement.executeQuery(query));assertEquals("55P03",denied.getSQLState());
                }
            }catch(java.sql.SQLException failure){throw new IllegalStateException("Disposable lock observer failed");}
            return null;
        });
    }
}
