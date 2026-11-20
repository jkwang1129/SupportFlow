package io.supportflow.customer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportflow.common.auth.CustomerInternalCredential;
import io.supportflow.common.context.TenantContext;
import io.supportflow.common.exception.GlobalExceptionHandler;
import io.supportflow.common.readiness.*;
import io.supportflow.customer.controller.internal.RuntimeReplyController;
import io.supportflow.customer.security.*;
import io.supportflow.customer.service.bot.*;
import io.supportflow.customer.testkit.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** Actual controller/authentication/client/PG transactions; authority server is a synthetic HTTP fault fixture. */
@EnabledIfEnvironmentVariable(named="SUPPORTFLOW_RLS_TEST_URL",matches=".+")
class RuntimeReplyPostgresTest extends SessionPostgresFixture {
    private static final ObjectMapper JSON=new ObjectMapper();
    private AgentReplyArguments reply;
    private String path;
    private RuntimeReceiverStub server;
    private AgentRuntimeClient client;
    private MockMvc mvc;
    private final AtomicInteger authorityStatus=new AtomicInteger(200);
    private final AtomicBoolean probe=new AtomicBoolean(true);
    private Runnable onAuthority=()->{};
    private <T> T tx(Supplier<T> call){return scoped(()->transaction.execute(status->call.get()));}
    @BeforeEach void setupResultHttp() throws Exception {
        reply=tx(()->{
            var outbox=new AgentEventOutbox(jdbc);var snapshots=new AgentSourceSnapshots(jdbc);var run=UUID.randomUUID();
            var source=service.appendMessage("TENANT_A",sessionNo,1,null,1,"private synthetic source",1,"reply-http-source");
            var event=outbox.enqueue("TENANT_A",Long.parseLong(source.msgId()),"runtime-v1",500);
            var lease=outbox.claim("TENANT_A","reply-http-test",30);outbox.acknowledge(lease,event.eventId(),run);
            var snapshot=snapshots.capture("TENANT_A",event.eventId(),run);
            return new AgentReplyArguments("TENANT_A",run,event.eventId(),snapshot.snapshotId(),"private synthetic reply");
        });
        clearInvocations(billing,brain);committedEvents.set(0);
        path="/api/customer/internal/runtime/events/"+reply.eventId()+"/runs/"+reply.runId()+"/reply";
        server=new RuntimeReceiverStub(request->{
            if(probe.get()){
                assertEquals(0,pool.getHikariPoolMXBean().getActiveConnections());
                tx(()->jdbc.queryForObject("SELECT id FROM session WHERE id=? FOR UPDATE NOWAIT",Long.class,sessionId));
            }
            assertFalse(new String(request.body(),StandardCharsets.UTF_8).contains(reply.content()));
            assertNull(request.headers().get("Authorization"));
            var body=JSON.readTree(request.body());assertEquals(8,body.size());
            assertEquals(reply.argumentsSha256(),body.get("arguments_sha256").textValue());
            onAuthority.run();
            if(authorityStatus.get()!=200)return new RuntimeReceiverStub.Reply(authorityStatus.get(),"synthetic-private-upstream-error");
            return new RuntimeReceiverStub.Reply(200,AgentActionPermitFixtures.grant(reply,"reply-worker",1,Instant.now().plusSeconds(4),RuntimeSourceWire.KEY));
        });
        client=new AgentRuntimeClient(server.endpoint(),RuntimeSourceWire.KEY);
        var registry=new InterceptorRegistry(){HandlerInterceptor[] values(){return getInterceptors().stream().map(HandlerInterceptor.class::cast).toArray(HandlerInterceptor[]::new);}};
        new CustomerInternalAccess("","","").addInterceptors(registry);
        var readiness=new SchemaReadiness(jdbc.getDataSource(),Map.of("agent_result_receipt","tenant_code,run_id,arguments_sha256"));
        mvc=MockMvcBuilders.standaloneSetup(new RuntimeReplyController(jdbc,transaction.getTransactionManager(),service,client))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new RuntimeSourceAccessFilter(RuntimeSourceWire.KEY,true,true,"","",""),new SchemaReadinessFilter(readiness))
                .addInterceptors(registry.values()).build();
    }
    private String body(AgentReplyArguments value)throws Exception {
        return JSON.writeValueAsString(Map.of("snapshot_id",value.snapshotId().toString(),"owner","reply-worker","fence",1,
                "arguments_sha256",value.argumentsSha256(),"content",value.content(),"version","reply-v1"));
    }
    private MvcResult call(String method,String path,String tenant,String content)throws Exception {
        byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
        return mvc.perform(request(HttpMethod.valueOf(method),URI.create(path)).content(bytes).contentType("application/json")
                .header("X-Tenant-Code",tenant).header(CustomerInternalCredential.HEADER,RuntimeSourceWire.credential(method,path,tenant,bytes,Instant.now().getEpochSecond()+20))).andReturn();
    }
    private MvcResult post()throws Exception{return call("POST",path,"TENANT_A",body(reply));}
    private void admin(String sql){
        try(var connection=DriverManager.getConnection(System.getenv("SUPPORTFLOW_RLS_TEST_URL"),"supportflow",System.getenv("SUPPORTFLOW_RLS_TEST_MIGRATION_PASSWORD"));var statement=connection.createStatement()){statement.execute(sql);}
        catch(Exception error){throw new IllegalStateException("Disposable result API fixture administration failed",error);}
    }
    @Override @AfterEach void cleanup(){
        try{
            if(client!=null)client.close();if(server!=null)server.close();
            admin("DELETE FROM agent_result_receipt WHERE event_id IN(SELECT event_id FROM agent_event_outbox WHERE session_id="+sessionId+");DELETE FROM agent_source_snapshot WHERE event_id IN(SELECT event_id FROM agent_event_outbox WHERE session_id="+sessionId+");DELETE FROM agent_event_outbox WHERE session_id="+sessionId);
        }finally{super.cleanup();}
    }
    private void facts(int added,int receipts){tx(()->{
        assertEquals(added+1,jdbc.queryForObject("SELECT count(*) FROM session_message WHERE session_id=?",Integer.class,sessionId));
        assertEquals((long)added+1,jdbc.queryForObject("SELECT last_msg_seq FROM session WHERE id=?",Long.class,sessionId));
        assertEquals(receipts,jdbc.queryForObject("SELECT count(*) FROM agent_result_receipt WHERE run_id=?",Integer.class,reply.runId()));return null;});}

    @Test void currentPermissionCreatesOneReplyAndDuplicateAckDoesNotReauthorizeAfterCommit()throws Exception{
        var created=post();assertEquals(200,created.getResponse().getStatus());
        var data=JSON.readTree(created.getResponse().getContentAsString()).get("data");assertEquals(10,data.size());assertEquals(2,data.get("seq").longValue());
        assertFalse(created.getResponse().getContentAsString().contains(reply.content()));
        authorityStatus.set(503);tx(()->{jdbc.update("UPDATE session SET status=4,agent_id=99 WHERE id=?",sessionId);return null;});
        assertEquals(data,JSON.readTree(post().getResponse().getContentAsString()).get("data"));
        assertEquals(data,JSON.readTree(call("GET",path,"TENANT_A","").getResponse().getContentAsString()).get("data"));
        assertEquals(1,server.calls.get());assertNull(server.failure.get());facts(1,1);verify(billing,times(1)).countMessage("TENANT_A");assertEquals(1,committedEvents.get());
    }
    @Test void changedReplyConflictsWithoutAnotherPermissionRequest()throws Exception{
        assertEquals(200,post().getResponse().getStatus());
        var changed=new AgentReplyArguments(reply.tenant(),reply.runId(),reply.eventId(),reply.snapshotId(),"different reply");
        assertEquals(409,call("POST",path,"TENANT_A",body(changed)).getResponse().getStatus());
        assertEquals(1,server.calls.get());facts(1,1);verify(billing,times(1)).countMessage("TENANT_A");
    }
    @Test void crossTenantWrongEventAnonymousAndMissingSchemaNeverRevealReceipts()throws Exception{
        assertEquals(200,post().getResponse().getStatus());
        assertEquals(404,call("GET",path,"TENANT_B","").getResponse().getStatus());
        assertEquals(404,call("GET",path.replace(reply.eventId().toString(),UUID.randomUUID().toString()),"TENANT_A","").getResponse().getStatus());
        admin("ALTER TABLE agent_result_receipt RENAME TO hidden_result_receipt");
        try{
            assertEquals(401,mvc.perform(request(HttpMethod.POST,URI.create(path)).content(body(reply))).andReturn().getResponse().getStatus());
            var blocked=post();assertEquals(503,blocked.getResponse().getStatus());assertFalse(blocked.getResponse().getContentAsString().contains("receipt"));
        }finally{admin("ALTER TABLE hidden_result_receipt RENAME TO agent_result_receipt");}
    }
    @Test void authorityFailureNeverWritesAndPrivateUpstreamErrorsAreNotRelayed()throws Exception{
        for(int status:new int[]{401,404,409,503}){
            authorityStatus.set(status);var denied=post();assertEquals(status==401?503:status,denied.getResponse().getStatus());
            assertFalse(denied.getResponse().getContentAsString().contains("synthetic-private-upstream-error"));facts(0,0);
        }verifyNoInteractions(billing);assertEquals(0,committedEvents.get());
    }
    @Test void resourceRevokedDuringPermissionRpcCannotReceiveAReply()throws Exception{
        onAuthority=()->tx(()->{jdbc.update("UPDATE session SET agent_id=99 WHERE id=?",sessionId);return null;});
        assertEquals(404,post().getResponse().getStatus());facts(0,0);verifyNoInteractions(billing);assertNull(server.failure.get());
    }
    @Test void receiptInsertPermissionFailureRollsBackMessageThenRetryCanCommit()throws Exception{
        admin("REVOKE INSERT ON agent_result_receipt FROM supportflow_customer_tenant");
        try{var failed=post();assertEquals(503,failed.getResponse().getStatus());assertFalse(failed.getResponse().getContentAsString().contains("INSERT"));}
        finally{admin("GRANT INSERT ON agent_result_receipt TO supportflow_customer_tenant");}
        facts(0,0);verifyNoInteractions(billing);assertEquals(200,post().getResponse().getStatus());facts(1,1);verify(billing,times(1)).countMessage("TENANT_A");
    }
    @Test void concurrentAuthenticatedPostsShareOneReplyAndResultReceipt()throws Exception{
        probe.set(false);var start=new CyclicBarrier(4);
        try(var workers=Executors.newFixedThreadPool(4)){
            var futures=java.util.stream.IntStream.range(0,4).mapToObj(i->workers.submit(()->{start.await(5,TimeUnit.SECONDS);return post();})).toList();
            String message=null;
            for(var future:futures){var result=future.get(12,TimeUnit.SECONDS);assertEquals(200,result.getResponse().getStatus());
                String id=JSON.readTree(result.getResponse().getContentAsString()).get("data").get("message_id").textValue();if(message==null)message=id;else assertEquals(message,id);}
        }facts(1,1);verify(billing,times(1)).countMessage("TENANT_A");assertEquals(1,committedEvents.get());assertNull(server.failure.get());
    }
}
