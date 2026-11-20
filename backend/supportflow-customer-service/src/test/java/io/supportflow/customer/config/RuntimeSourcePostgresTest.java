package io.supportflow.customer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportflow.common.auth.CustomerInternalCredential;
import io.supportflow.common.exception.GlobalExceptionHandler;
import io.supportflow.common.readiness.SchemaReadiness;
import io.supportflow.common.readiness.SchemaReadinessFilter;
import io.supportflow.customer.controller.internal.RuntimeSourceController;
import io.supportflow.customer.security.CustomerInternalAccess;
import io.supportflow.customer.security.RuntimeSourceAccessFilter;
import io.supportflow.customer.service.bot.AgentEventOutbox;
import io.supportflow.customer.testkit.RuntimeSourceWire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

@EnabledIfEnvironmentVariable(named="SUPPORTFLOW_RLS_TEST_URL", matches=".+")
class RuntimeSourcePostgresTest extends SessionPostgresFixture {
    private static final ObjectMapper JSON = new ObjectMapper();
    private AgentEventOutbox.Event event;
    private UUID run;
    private String path;
    private MockMvc mvc;
    private <T> T tx(Supplier<T> action) { return scoped(() -> transaction.execute(status -> action.get())); }
    @BeforeEach void prepareSourceHttp() {
        var outbox = new AgentEventOutbox(jdbc); run = UUID.randomUUID();
        event = tx(() -> {
            var message = service.appendMessage("TENANT_A",sessionNo,1,null,1,"synthetic private input",1,"source-api");
            var value = outbox.enqueue("TENANT_A",Long.parseLong(message.msgId()),"runtime-v1",500);
            var lease = outbox.claim("TENANT_A","source-api-test",30); outbox.acknowledge(lease,value.eventId(),run); return value;
        });
        path = "/api/customer/internal/runtime/events/"+event.eventId()+"/runs/"+run+"/snapshot";
        var registry = new InterceptorRegistry() {
            HandlerInterceptor[] values() { return getInterceptors().stream().map(HandlerInterceptor.class::cast).toArray(HandlerInterceptor[]::new); }
        };
        new CustomerInternalAccess("","","").addInterceptors(registry);
        var readiness = new SchemaReadiness(jdbc.getDataSource(), Map.of("agent_source_snapshot","tenant_code,event_id,payload_hash,payload_json"));
        mvc = MockMvcBuilders.standaloneSetup(new RuntimeSourceController(jdbc,transaction.getTransactionManager()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RuntimeSourceAccessFilter(RuntimeSourceWire.KEY,true,"","",""), new SchemaReadinessFilter(readiness))
                .addInterceptors(registry.values()).build();
    }
    private org.springframework.test.web.servlet.MvcResult signed(String method,String target,String tenant,String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return mvc.perform(request(HttpMethod.valueOf(method), URI.create(target)).content(bytes).contentType("application/json")
                .header("X-Tenant-Code",tenant).header(CustomerInternalCredential.HEADER,
                        RuntimeSourceWire.credential(method,target,tenant,bytes,Instant.now().getEpochSecond()+20))).andReturn();
    }
    private int code(org.springframework.test.web.servlet.MvcResult result) throws Exception { return JSON.readTree(result.getResponse().getContentAsString()).get("code").intValue(); }
    private void admin(String sql) {
        try (var connection = DriverManager.getConnection(System.getenv("SUPPORTFLOW_RLS_TEST_URL"), "supportflow",System.getenv("SUPPORTFLOW_RLS_TEST_MIGRATION_PASSWORD")); var statement = connection.createStatement()) { statement.execute(sql); }
        catch (Exception failure) { throw new IllegalStateException("Disposable fixture administration failed", failure); }
    }
    @Override @AfterEach void cleanup() {
        try { admin("DELETE FROM agent_source_snapshot WHERE event_id IN(SELECT event_id FROM agent_event_outbox WHERE session_id="+sessionId+"); DELETE FROM agent_event_outbox WHERE session_id="+sessionId); }
        finally { super.cleanup(); }
    }

    @Test void realControllerFilterAndDatabaseCreateAndReadTheSameSnapshot() throws Exception {
        var created = signed("POST",path,"TENANT_A","{}"); assertEquals(200,created.getResponse().getStatus()); assertEquals(0,code(created));
        var first = JSON.readTree(created.getResponse().getContentAsString()).get("data");
        assertEquals(event.eventId().toString(),first.get("source_event_id").textValue()); assertEquals(run.toString(),first.get("run_id").textValue());
        var read = signed("GET",path,"TENANT_A",""); assertEquals(0,code(read));
        assertEquals(first,JSON.readTree(read.getResponse().getContentAsString()).get("data"));
        assertEquals(0,code(signed("POST",path,"TENANT_A","{}")));
        assertEquals(1,tx(() -> jdbc.queryForObject("SELECT count(*) FROM agent_source_snapshot WHERE event_id=?",Integer.class,event.eventId())));
    }

    @Test void foreignRunTenantAndBodyFieldsCannotAuthorizeAnotherResource() throws Exception {
        assertEquals(40402,code(signed("POST",path,"TENANT_B","{}")));
        assertEquals(40402,code(signed("POST",path.replace(run.toString(),UUID.randomUUID().toString()),"TENANT_A","{}")));
        assertEquals(400,signed("POST",path,"TENANT_A","{\"tenant_code\":\"TENANT_B\"}").getResponse().getStatus());
        assertEquals(0,tx(() -> jdbc.queryForObject("SELECT count(*) FROM agent_source_snapshot WHERE event_id=?",Integer.class,event.eventId())));
    }

    @Test void authenticationPrecedesReadinessAndMissingSchemaNeverLeaksSql() throws Exception {
        admin("ALTER TABLE agent_source_snapshot RENAME TO hidden_source_snapshot");
        try {
            var anonymous = mvc.perform(request(HttpMethod.POST, URI.create(path)).content("{}")).andReturn();
            assertEquals(401,anonymous.getResponse().getStatus());
            var blocked = signed("POST",path,"TENANT_A","{}"); assertEquals(503,blocked.getResponse().getStatus());
            assertFalse(blocked.getResponse().getContentAsString().contains("snapshot"));
        } finally { admin("ALTER TABLE hidden_source_snapshot RENAME TO agent_source_snapshot"); }
        assertEquals(0,code(signed("POST",path,"TENANT_A","{}")));
    }

    @Test void revocationAndChangedInputRemainClosedThroughTheHttpApi() throws Exception {
        assertEquals(0,code(signed("POST",path,"TENANT_A","{}")));
        admin("UPDATE session SET agent_id=12345 WHERE id="+sessionId);
        assertEquals(40402,code(signed("GET",path,"TENANT_A","")));
        admin("UPDATE session SET agent_id=NULL WHERE id="+sessionId+"; UPDATE session_message SET content='edited' WHERE id="+event.messageId());
        var changed = signed("GET",path,"TENANT_A",""); assertEquals(40902,code(changed));
        assertFalse(changed.getResponse().getContentAsString().contains("synthetic private input"));
    }

    @Test void insertPrivilegeFailureIs503AndCanRecoverWithoutAReplyOrSqlLeak() throws Exception {
        admin("REVOKE INSERT ON agent_source_snapshot FROM supportflow_customer_tenant");
        try {
            var blocked = signed("POST",path,"TENANT_A","{}"); assertEquals(503,blocked.getResponse().getStatus());
            assertFalse(blocked.getResponse().getContentAsString().contains("agent_source_snapshot"));
            assertFalse(blocked.getResponse().getContentAsString().contains("INSERT"));
        } finally { admin("GRANT INSERT ON agent_source_snapshot TO supportflow_customer_tenant"); }
        assertEquals(0,code(signed("POST",path,"TENANT_A","{}")));
    }
}
