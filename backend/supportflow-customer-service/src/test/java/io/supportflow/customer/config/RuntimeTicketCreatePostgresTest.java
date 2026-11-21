package io.supportflow.customer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportflow.common.auth.CustomerInternalCredential;
import io.supportflow.common.exception.GlobalExceptionHandler;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.common.readiness.*;
import io.supportflow.customer.controller.internal.RuntimeTicketCreateController;
import io.supportflow.customer.internal.*;
import io.supportflow.customer.mapper.*;
import io.supportflow.customer.security.*;
import io.supportflow.customer.service.bot.*;
import io.supportflow.customer.service.customer.CustomerDynamics;
import io.supportflow.customer.service.notification.NotificationService;
import io.supportflow.customer.service.ticket.TicketService;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** Real restricted business transactions/auth filter and real authority HTTP; only authority data is synthetic. */
@EnabledIfEnvironmentVariable(named="SUPPORTFLOW_RLS_TEST_URL",matches=".+")
class RuntimeTicketCreatePostgresTest extends SessionPostgresFixture {
    private static final ObjectMapper JSON=new ObjectMapper();
    private AgentTicketArguments args;
    private RuntimeReceiverStub server;
    private AgentRuntimeClient client;
    private TicketService tickets;
    private MockMvc mvc;
    private String path;
    private final AtomicInteger authorityStatus=new AtomicInteger(200);
    private final AtomicBoolean probe=new AtomicBoolean(true);
    private AgentActionPermit.Operation operation=AgentActionPermit.Operation.TICKET_CREATE;
    private Runnable onAuthority=()->{};
    private <T>T tx(Supplier<T> work){return scoped(()->transaction.execute(status->work.get()));}
    @BeforeEach void prepareApi()throws Exception{
        for(Class<?> mapper:List.of(TicketMapper.class,TicketEventMapper.class,TicketSlaRuleMapper.class,CustomerMapper.class,CustomerEventMapper.class))
            if(!sql.getConfiguration().hasMapper(mapper))sql.getConfiguration().addMapper(mapper);
        var generator=new SnowflakeIdGenerator(39);
        tickets=new TicketService(sql.getMapper(TicketMapper.class),sql.getMapper(TicketEventMapper.class),sql.getMapper(TicketSlaRuleMapper.class),service,
            mock(RealtimeNotifyClient.class),mock(UserRoleClient.class),mock(NotificationService.class),sql.getMapper(CustomerMapper.class),
            new CustomerDynamics(sql.getMapper(CustomerEventMapper.class),generator),emptyProvider(),generator,emptyProvider(),emptyProvider(),mock(TenantListClient.class));
        args=tx(()->{
            var outbox=new AgentEventOutbox(jdbc);var run=UUID.randomUUID();
            var message=service.appendMessage("TENANT_A",sessionNo,1,null,1,"synthetic ticket API source",1,"ticket-api-source");
            var event=outbox.enqueue("TENANT_A",Long.parseLong(message.msgId()),"runtime-v1",500);
            outbox.acknowledge(outbox.claim("TENANT_A","ticket-api-fixture",30),event.eventId(),run);
            var snapshot=new AgentSourceSnapshots(jdbc).capture("TENANT_A",event.eventId(),run);
            return new AgentTicketArguments("TENANT_A",run,event.eventId(),snapshot.snapshotId(),"PRIVATE TICKET TITLE","PRIVATE TICKET CONTENT");
        });clearInvocations(billing,brain);committedEvents.set(0);
        path="/api/customer/internal/runtime/events/"+args.eventId()+"/runs/"+args.runId()+"/ticket-create";
        server=new RuntimeReceiverStub(request->{
            if(probe.get()){
                assertEquals(0,pool.getHikariPoolMXBean().getActiveConnections());
                tx(()->jdbc.queryForObject("SELECT id FROM session WHERE id=? FOR UPDATE NOWAIT",Long.class,sessionId));
            }
            String raw=new String(request.body(),StandardCharsets.UTF_8);assertFalse(raw.contains(args.title()));assertFalse(raw.contains(args.content()));assertNull(request.headers().get("Authorization"));
            var body=JSON.readTree(raw);assertEquals(8,body.size());assertEquals("ticket.create",body.get("operation").textValue());
            assertEquals(AgentTicketArguments.VERSION,body.get("version").textValue());assertEquals(args.argumentsSha256(),body.get("arguments_sha256").textValue());
            onAuthority.run();if(authorityStatus.get()!=200)return new RuntimeReceiverStub.Reply(authorityStatus.get(),"PRIVATE UPSTREAM ERROR");
            return new RuntimeReceiverStub.Reply(200,AgentActionPermitFixtures.grant(args,"ticket-worker",1,Instant.now().plusSeconds(4),RuntimeSourceWire.KEY,operation));
        });client=new AgentRuntimeClient(server.endpoint(),RuntimeSourceWire.KEY);mvc=api(client,true);
    }
    private MockMvc api(AgentRuntimeClient authority,boolean write){
        var registry=new InterceptorRegistry(){HandlerInterceptor[] values(){return getInterceptors().stream().map(HandlerInterceptor.class::cast).toArray(HandlerInterceptor[]::new);}};
        new CustomerInternalAccess("","","").addInterceptors(registry);
        return MockMvcBuilders.standaloneSetup(new RuntimeTicketCreateController(jdbc,transaction.getTransactionManager(),tickets,authority))
            .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new RuntimeSourceAccessFilter(RuntimeSourceWire.KEY,false,false,true,write,"","",""),
                new SchemaReadinessFilter(new SchemaReadiness(jdbc.getDataSource(),Map.of("agent_ticket_receipt","tenant_code,run_id,arguments_sha256"))))
            .addInterceptors(registry.values()).build();
    }
    private String body(AgentTicketArguments value)throws Exception{
        return JSON.writeValueAsString(Map.of("snapshot_id",value.snapshotId().toString(),"owner","ticket-worker","fence",1,"arguments_sha256",value.argumentsSha256(),
            "title",value.title(),"content",value.content(),"version",AgentTicketArguments.VERSION));
    }
    private MvcResult call(MockMvc target,String method,String route,String tenant,String content)throws Exception{
        byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
        return target.perform(request(HttpMethod.valueOf(method),URI.create(route)).content(bytes).contentType("application/json").header("X-Tenant-Code",tenant)
            .header(CustomerInternalCredential.HEADER,RuntimeSourceWire.credential(method,route,tenant,bytes,Instant.now().getEpochSecond()+20))).andReturn();
    }
    private MvcResult post()throws Exception{return call(mvc,"POST",path,"TENANT_A",body(args));}
    private void facts(int expected){tx(()->{
        assertEquals(expected,jdbc.queryForObject("SELECT count(*) FROM ticket WHERE source_session_id=?",Integer.class,sessionId));
        assertEquals(expected,jdbc.queryForObject("SELECT count(*) FROM ticket_event WHERE ticket_id IN(SELECT id FROM ticket WHERE source_session_id=?)",Integer.class,sessionId));
        assertEquals(expected,jdbc.queryForObject("SELECT count(*) FROM agent_ticket_receipt WHERE run_id=?",Integer.class,args.runId()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM session_message WHERE session_id=?",Integer.class,sessionId));return null;
    });verifyNoInteractions(billing,brain);assertEquals(0,committedEvents.get());}
    private void admin(String query){
        try(var db=DriverManager.getConnection(System.getenv("SUPPORTFLOW_RLS_TEST_URL"),"supportflow",System.getenv("SUPPORTFLOW_RLS_TEST_MIGRATION_PASSWORD"));var sql=db.createStatement()){sql.execute(query);}
        catch(Exception failure){throw new IllegalStateException("Disposable ticket API administration failed",failure);}
    }
    @Override @AfterEach void cleanup(){
        try{
            if(client!=null)client.close();if(server!=null)server.close();
            if(args!=null)admin("DELETE FROM agent_ticket_receipt WHERE run_id='"+args.runId()+"'; DELETE FROM ticket_event WHERE ticket_id IN(SELECT id FROM ticket WHERE source_session_id="+sessionId+"); DELETE FROM ticket WHERE source_session_id="+sessionId+"; DELETE FROM agent_source_snapshot WHERE event_id='"+args.eventId()+"'; DELETE FROM agent_event_outbox WHERE event_id='"+args.eventId()+"'");
        }finally{super.cleanup();}
    }
    @Test void currentPermitCommitsOnceAndReplayDoesNotNeedCurrentSourceOrAuthority()throws Exception{
        var result=post();assertEquals(200,result.getResponse().getStatus());var data=JSON.readTree(result.getResponse().getContentAsString()).get("data");
        assertEquals(9,data.size());assertTrue(data.get("ticket_id").isTextual());assertEquals("ticket.create",data.get("operation").textValue());
        assertFalse(result.getResponse().getContentAsString().contains("PRIVATE"));
        authorityStatus.set(503);tx(()->{jdbc.update("UPDATE session SET status=4,agent_id=99 WHERE id=?",sessionId);return null;});
        assertEquals(data,JSON.readTree(post().getResponse().getContentAsString()).get("data"));
        assertEquals(data,JSON.readTree(call(mvc,"GET",path,"TENANT_A","").getResponse().getContentAsString()).get("data"));
        assertEquals(1,server.calls.get());assertNull(server.failure.get());facts(1);
    }
    @Test void changedArgumentsConflictBeforeAnotherAuthorityCall()throws Exception{
        assertEquals(200,post().getResponse().getStatus());var changed=new AgentTicketArguments(args.tenant(),args.runId(),args.eventId(),args.snapshotId(),"changed",args.content());
        assertEquals(409,call(mvc,"POST",path,"TENANT_A",body(changed)).getResponse().getStatus());assertEquals(1,server.calls.get());facts(1);
    }
    @Test void readOnlyApiWorksWhenWritesAndAuthorityClientAreDisabled()throws Exception{
        assertEquals(200,post().getResponse().getStatus());var readOnly=api(null,false);
        assertEquals(200,call(readOnly,"GET",path,"TENANT_A","").getResponse().getStatus());assertEquals(503,call(readOnly,"POST",path,"TENANT_A",body(args)).getResponse().getStatus());
        assertEquals(1,server.calls.get());facts(1);
    }
    @Test void missingReceiptAndOtherTenantsDoNotRequestNewAuthority()throws Exception{
        assertEquals(404,call(mvc,"GET",path,"TENANT_A","").getResponse().getStatus());assertEquals(0,server.calls.get());
        assertEquals(200,post().getResponse().getStatus());
        assertEquals(404,call(mvc,"GET",path,"TENANT_B","").getResponse().getStatus());
        assertEquals(404,call(mvc,"GET",path.replace(args.eventId().toString(),UUID.randomUUID().toString()),"TENANT_A","").getResponse().getStatus());
        assertEquals(1,server.calls.get());facts(1);
    }
    @Test void authorityFailuresAreBoundedAndDoNotExposeUpstreamBody()throws Exception{
        for(int code:new int[]{401,404,409,503}){
            authorityStatus.set(code);var result=post();assertEquals(code==401?503:code,result.getResponse().getStatus());
            assertFalse(result.getResponse().getContentAsString().contains("PRIVATE"));facts(0);
        }assertNull(server.failure.get());
    }
    @Test void validMacReplyOperationCannotAuthorizeTicketCreation()throws Exception{
        operation=AgentActionPermit.Operation.SESSION_REPLY;assertEquals(503,post().getResponse().getStatus());facts(0);assertNull(server.failure.get());
    }
    @Test void currentSourceRevokedDuringRpcCannotCreateBusinessRows()throws Exception{
        onAuthority=()->tx(()->{jdbc.update("UPDATE session SET agent_id=99 WHERE id=?",sessionId);return null;});
        assertEquals(404,post().getResponse().getStatus());facts(0);assertNull(server.failure.get());
    }
    @Test void receiptFailureRollsBackTicketAndTimelineThenExplicitRetryCanCommit()throws Exception{
        admin("REVOKE INSERT ON agent_ticket_receipt FROM supportflow_customer_tenant");
        try{var result=post();assertEquals(503,result.getResponse().getStatus());assertFalse(result.getResponse().getContentAsString().contains("INSERT"));}
        finally{admin("GRANT INSERT ON agent_ticket_receipt TO supportflow_customer_tenant");}
        facts(0);assertEquals(200,post().getResponse().getStatus());facts(1);
    }
    @Test void concurrentSignedPostsShareOneBusinessResult()throws Exception{
        probe.set(false);var barrier=new CyclicBarrier(4);
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var futures=java.util.stream.IntStream.range(0,4).mapToObj(i->workers.submit(()->{barrier.await(5,TimeUnit.SECONDS);return post();})).toList();String id=null;
            for(var future:futures){var result=future.get(15,TimeUnit.SECONDS);assertEquals(200,result.getResponse().getStatus());
                String next=JSON.readTree(result.getResponse().getContentAsString()).get("data").get("ticket_id").textValue();if(id==null)id=next;else assertEquals(id,next);}
        }facts(1);assertNull(server.failure.get());
    }
    @Test void anonymousAndMissingSchemaCannotReachAuthorityOrLeakSql()throws Exception{
        admin("ALTER TABLE agent_ticket_receipt RENAME TO hidden_ticket_receipt");
        try{
            assertEquals(401,mvc.perform(request(HttpMethod.POST,URI.create(path)).content(body(args))).andReturn().getResponse().getStatus());
            var result=post();assertEquals(503,result.getResponse().getStatus());assertFalse(result.getResponse().getContentAsString().contains("receipt"));assertEquals(0,server.calls.get());
        }finally{admin("ALTER TABLE hidden_ticket_receipt RENAME TO agent_ticket_receipt");}
    }
}
