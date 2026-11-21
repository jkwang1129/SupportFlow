package io.supportflow.customer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.supportflow.common.context.TenantContext;
import io.supportflow.customer.service.bot.AgentDispatchSchedule;
import io.supportflow.customer.service.bot.AgentEventDispatcher;
import io.supportflow.customer.service.bot.AgentEventOutbox;
import io.supportflow.customer.service.bot.AgentRuntimeClient;
import io.supportflow.customer.service.bot.AgentRuntimeProtocol;
import io.supportflow.customer.testkit.RuntimeReceiverStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Actual Python receiver and actual restricted roles; script owns the whole disposable cluster. */
@EnabledIfEnvironmentVariable(named="SUPPORTFLOW_RUNTIME_DELIVERY_URL", matches=".+")
class AgentEventDeliveryPostgresTest {
    private static final AtomicLong IDS = new AtomicLong(9_900_000);
    private static final ObjectMapper JSON = new ObjectMapper();
    private HikariDataSource pool;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private AgentEventOutbox outbox;
    private AgentEventOutbox.Event event;
    private URI endpoint;
    private String secret;

    @BeforeEach void prepare() {
        assertTrue(System.getenv("SUPPORTFLOW_DELIVERY_TEST_PROJECT").matches("supportflow-schema-test-[0-9a-f]{12}"));
        String url = System.getenv("SUPPORTFLOW_DELIVERY_TEST_CUSTOMER_JDBC");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/customer_db"));
        endpoint = URI.create(System.getenv("SUPPORTFLOW_RUNTIME_DELIVERY_URL"));
        assertEquals("127.0.0.1", endpoint.getHost()); assertEquals("http", endpoint.getScheme());
        secret = System.getenv("SUPPORTFLOW_RUNTIME_CUSTOMER_TEST_SECRET");
        var config = new HikariConfig(); config.setJdbcUrl(url); config.setUsername("supportflow_customer");
        config.setPassword(System.getenv("SUPPORTFLOW_DELIVERY_TEST_CUSTOMER_PASSWORD")); config.setMaximumPoolSize(3);
        pool = new HikariDataSource(config);
        var source = new TenantRlsConfig.TenantRlsDataSource(pool);
        jdbc = new JdbcTemplate(source); transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(10); outbox = new AgentEventOutbox(jdbc);
        long session = IDS.incrementAndGet(), message = IDS.incrementAndGet();
        event = tx(() -> {
            String sessionNo = "JDEL_" + UUID.randomUUID().toString().replace("-", "");
            jdbc.update("INSERT INTO session(id,tenant_code,session_no,channel_id,status,start_time,last_msg_seq) VALUES(?,'TENANT_A',?,1,2,CURRENT_TIMESTAMP,1)", session, sessionNo);
            jdbc.update("INSERT INTO session_message(id,tenant_code,session_id,msg_no,seq,msg_type,sender_type,content,send_time) VALUES(?,'TENANT_A',?,?,1,1,1,'synthetic-private-source',CURRENT_TIMESTAMP)", message, session, "JDEL_MSG_" + message);
            return outbox.enqueue("TENANT_A", message, "runtime-v1", 500);
        });
    }
    private <T> T tx(Supplier<T> action) { return TenantContext.bind("TENANT_A", () -> transaction.execute(status -> action.get())); }
    private <T> T tx(String tenant, Supplier<T> action) { return TenantContext.bind(tenant, () -> transaction.execute(status -> action.get())); }
    private AgentEventOutbox.Event stored() { return tx(() -> outbox.getByMessage("TENANT_A", event.messageId())); }
    private AgentEventDispatcher dispatcher(AgentRuntimeClient client) { return new AgentEventDispatcher(outbox, transaction.getTransactionManager(), client); }
    @AfterEach void close() { if (pool != null) pool.close(); } // Only script removes disposable cluster, no destructive app grants.

    @Test void realSignedAdmissionReturnsTheSamePendingRunAndHidesItFromAnotherTenant() throws Exception {
        try (var client = new AgentRuntimeClient(endpoint, secret); var http = HttpClient.newHttpClient()) {
            long session = IDS.incrementAndGet(), message = IDS.incrementAndGet();
            var foreign = tx("TENANT_B", () -> {
                jdbc.update("INSERT INTO session(id,tenant_code,session_no,channel_id,status,start_time,last_msg_seq) VALUES(?,'TENANT_B',?,1,2,CURRENT_TIMESTAMP,1)",
                        session, "OTHER_DISPATCH_" + session);
                jdbc.update("INSERT INTO session_message(id,tenant_code,session_id,msg_no,seq,msg_type,sender_type,content,send_time) VALUES(?,'TENANT_B',?,?,1,1,1,'synthetic foreign source',CURRENT_TIMESTAMP)",
                        message, session, "OTHER_DISPATCH_MSG_" + message);
                return outbox.enqueue("TENANT_B", message, "runtime-v1", 500);
            });
            var schedule = new AgentDispatchSchedule(outbox, transaction.getTransactionManager(), "TENANT_A", true,
                    endpoint.getScheme() + "://" + endpoint.getRawAuthority(), secret);
            try {
                schedule.start();
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (!"DELIVERED".equals(stored().state()) && System.nanoTime() < deadline) {
                    Thread.sleep(40);
                }
            } finally {
                schedule.stop();
            }
            assertEquals(foreign, tx("TENANT_B", () -> outbox.getByMessage("TENANT_B", message)));
            var delivered = stored(); assertEquals("DELIVERED", delivered.state()); assertNotNull(delivered.runId());
            assertEquals(delivered.runId(), client.enqueue(event).runId());
            URI status = URI.create(endpoint + "/" + delivered.runId());
            var own = http.send(AgentRuntimeProtocol.request(status, "GET", "TENANT_A", new byte[0], secret), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, own.statusCode());
            var row = JSON.readTree(own.body()); assertEquals("PENDING", row.get("state").textValue());
            assertEquals(event.eventId().toString(), row.get("source_event_id").textValue());
            assertEquals(0, row.get("model_calls").intValue()); assertEquals(0, row.get("tool_calls").intValue());
            assertFalse(own.body().contains("synthetic-private-source"));
            assertEquals(404, http.send(AgentRuntimeProtocol.request(status, "GET", "TENANT_B", new byte[0], secret), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(401, http.send(HttpRequest.newBuilder(status).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test void lostReplyAfterActualPythonCommitResendsTheSameEventAndAcceptsOneRun() throws Exception {
        var calls = new AtomicInteger(); var acceptedRun = new AtomicReference<UUID>(); var upstreamStatus = new AtomicInteger(-1);
        try (var forward = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(); var proxy = new RuntimeReceiverStub(request -> {
            var builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(4))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(request.body()));
            for (String name : new String[]{"X-Tenant-Code", "X-SupportFlow-AI-Auth", "X-Request-Id", "Content-Type"})
                builder.header(name, request.headers().get(name).getFirst());
            var real = forward.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            upstreamStatus.set(real.statusCode());
            assertEquals(202, real.statusCode());
            UUID run = UUID.fromString(JSON.readTree(real.body()).get("run_id").textValue());
            UUID previous = acceptedRun.getAndSet(run); if (previous != null) assertEquals(previous, run);
            return new RuntimeReceiverStub.Reply(202, real.body(), "application/json", calls.incrementAndGet() == 1 ? 4000 : 0, null);
        }); var client = new AgentRuntimeClient(proxy.endpoint(), secret, Duration.ofSeconds(1))) {
            assertEquals(AgentEventDispatcher.Result.RETRY, dispatcher(client).dispatchOne("TENANT_A"));
            assertEquals("DELIVERY_TIMEOUT", tx(() -> jdbc.queryForObject("SELECT error_code FROM agent_event_outbox WHERE event_id=?", String.class, event.eventId())),
                    () -> "upstreamStatus=" + upstreamStatus.get() + ", proxyFailure=" + (proxy.failure.get() == null ? "none"
                            : proxy.failure.get().getClass().getSimpleName() + ":" + java.util.Arrays.stream(proxy.failure.get().getStackTrace())
                            .filter(frame -> frame.getClassName().contains("AgentEventDeliveryPostgresTest")).findFirst().orElse(null)));
            tx(() -> { jdbc.update("UPDATE agent_event_outbox SET ready_at=clock_timestamp()-interval '1 second' WHERE event_id=?", event.eventId()); return null; });
            var result = dispatcher(client).dispatchOne("TENANT_A");
            assertEquals(AgentEventDispatcher.Result.DELIVERED, result, () ->
                    "receipt result=" + result + ", error=" + tx(() -> jdbc.queryForObject("SELECT error_code FROM agent_event_outbox WHERE event_id=?", String.class, event.eventId()))
                            + ", forwarded=" + calls.get() + ", proxyFailure=" + (proxy.failure.get() == null ? "none" : proxy.failure.get().getClass().getSimpleName()));
            assertEquals(2, stored().attempts()); assertEquals(acceptedRun.get(), stored().runId());
            assertEquals(2, calls.get()); assertNull(proxy.failure.get());
        }
    }
}
