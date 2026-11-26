package io.supportflow.customer.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportflow.common.auth.CustomerInternalCredential;
import io.supportflow.common.exception.GlobalExceptionHandler;
import io.supportflow.common.readiness.SchemaReadiness;
import io.supportflow.common.readiness.SchemaReadinessFilter;
import io.supportflow.customer.controller.internal.RuntimeCustomerProfileController;
import io.supportflow.customer.controller.internal.RuntimeSessionHistoryController;
import io.supportflow.customer.security.CustomerInternalAccess;
import io.supportflow.customer.security.RuntimeSourceAccessFilter;
import io.supportflow.customer.service.bot.AgentCustomerQueries;
import io.supportflow.customer.service.bot.AgentEventOutbox;
import io.supportflow.customer.service.bot.AgentSessionHistoryQueries;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

@EnabledIfEnvironmentVariable(named = "SUPPORTFLOW_RLS_TEST_URL", matches = ".+")
class RuntimeCustomerToolsPostgresTest extends SessionPostgresFixture {
    private static final ObjectMapper JSON = new ObjectMapper();

    private AgentEventOutbox.Event event;
    private UUID run;
    private String profilePath;
    private String historyPath;
    private long customerId;
    private long foreignCustomerId;
    private final List<Long> extraSessionIds = new ArrayList<>();
    private MockMvc mvc;

    private <T> T tx(Supplier<T> action) {
        return scoped(() -> transaction.execute(status -> action.get()));
    }

    @BeforeEach
    void prepareCustomerToolsHttp() {
        var outbox = new AgentEventOutbox(jdbc);
        run = UUID.randomUUID();
        customerId = IDS.incrementAndGet();
        foreignCustomerId = IDS.incrementAndGet();
        extraSessionIds.clear();

        event = tx(() -> {
            var message = service.appendMessage(
                    "TENANT_A", sessionNo, 1, null, 1, "查询我的会员等级与历史记录", 1, "profile-history-source");
            var value = outbox.enqueue("TENANT_A", Long.parseLong(message.msgId()), "runtime-v1", 500);
            var lease = outbox.claim("TENANT_A", "customer-tools-test", 30);
            outbox.acknowledge(lease, value.eventId(), run);
            return value;
        });

        profilePath = "/api/customer/internal/runtime/events/" + event.eventId() + "/runs/" + run + "/customer-profile";
        historyPath = "/api/customer/internal/runtime/events/" + event.eventId() + "/runs/" + run + "/session-history";

        var registry = new InterceptorRegistry() {
            HandlerInterceptor[] values() {
                return getInterceptors().stream().map(HandlerInterceptor.class::cast).toArray(HandlerInterceptor[]::new);
            }
        };
        new CustomerInternalAccess("", "", "").addInterceptors(registry);
        var readiness = new SchemaReadiness(
                jdbc.getDataSource(),
                Map.of(
                        "agent_source_snapshot", "tenant_code,event_id,payload_hash,payload_json",
                        "customer", "tenant_code,customer_no,level,points,risk_level",
                        "session", "tenant_code,session_no,customer_id,status",
                        "session_message", "tenant_code,session_id,seq,visible_to,msg_type"));
        mvc = MockMvcBuilders.standaloneSetup(
                        new RuntimeCustomerProfileController(jdbc, transaction.getTransactionManager()),
                        new RuntimeSessionHistoryController(jdbc, transaction.getTransactionManager()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(
                        new RuntimeSourceAccessFilter(RuntimeSourceWire.KEY, false, false, true, "", "", ""),
                        new SchemaReadinessFilter(readiness))
                .addInterceptors(registry.values())
                .build();
    }

    private MvcResult get(String target, String tenant) throws Exception {
        return mvc.perform(request(HttpMethod.GET, URI.create(target))
                        .header("X-Tenant-Code", tenant)
                        .header(CustomerInternalCredential.HEADER,
                                RuntimeSourceWire.credential(
                                        "GET", target, tenant, new byte[0], Instant.now().getEpochSecond() + 20)))
                .andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode success(String target) throws Exception {
        var result = get(target, "TENANT_A");
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(0, body(result).path("code").intValue());
        assertFalse(result.getResponse().getContentAsString().contains("INTERNAL-SECRET"));
        assertFalse(result.getResponse().getContentAsString().contains("13899998888"));
        return body(result).get("data");
    }

    private void denied(String target, int status, int code) throws Exception {
        var result = get(target, "TENANT_A");
        assertEquals(status, result.getResponse().getStatus());
        assertEquals(code, body(result).path("code").intValue());
        assertTrue(body(result).path("data").isNull());
        assertFalse(result.getResponse().getContentAsString().contains("INTERNAL-SECRET"));
    }

    private void admin(String sql) {
        try (var db = DriverManager.getConnection(
                System.getenv("SUPPORTFLOW_RLS_TEST_URL"),
                "supportflow",
                System.getenv("SUPPORTFLOW_RLS_TEST_MIGRATION_PASSWORD"));
             var statement = db.createStatement()) {
            statement.execute(sql);
        } catch (Exception failure) {
            throw new IllegalStateException("Disposable fixture administration failed", failure);
        }
    }

    private void bindCustomer() {
        // Re-create a fresh run/event after binding customer_id on the session so the immutable snapshot reflects bound state.
        tx(() -> {
            jdbc.update("""
                    INSERT INTO customer(id, tenant_code, customer_no, name, phone, level, customer_type,
                                         points, orders_count, ticket_count, session_count, risk_level, remark)
                    VALUES (?, 'TENANT_A', ?, '张三丰', '13899998888', 3, 2, 1280, 12, 4, 9, 2, 'INTERNAL-SECRET-REMARK')
                    """, customerId, "CUS_" + customerId);
            jdbc.update("""
                    INSERT INTO customer(id, tenant_code, customer_no, name, phone, remark)
                    VALUES (?, 'TENANT_A', ?, '李四', '13900001111', 'INTERNAL-SECRET-FOREIGN')
                    """, foreignCustomerId, "CUS_" + foreignCustomerId);
            jdbc.update("UPDATE session SET customer_id = ? WHERE id = ?", customerId, sessionId);
            jdbc.update("DELETE FROM agent_source_snapshot WHERE event_id = ?", event.eventId());
            return null;
        });
    }

    @Override
    @AfterEach
    void cleanup() {
        try {
            StringBuilder cleanupSql = new StringBuilder();
            for (Long extraId : extraSessionIds) {
                cleanupSql.append("DELETE FROM session_message WHERE session_id=").append(extraId).append("; ");
                cleanupSql.append("DELETE FROM session WHERE id=").append(extraId).append("; ");
            }
            cleanupSql.append("DELETE FROM agent_source_snapshot WHERE event_id IN (SELECT event_id FROM agent_event_outbox WHERE session_id=")
                    .append(sessionId).append("); ");
            cleanupSql.append("DELETE FROM agent_event_outbox WHERE session_id=").append(sessionId).append("; ");
            cleanupSql.append("DELETE FROM customer WHERE id IN (").append(customerId).append(",").append(foreignCustomerId).append(");");
            admin(cleanupSql.toString());
        } finally {
            super.cleanup();
        }
    }

    @Test
    void unboundVisitorReturnsDeterministicAnonymousProfileAndCurrentSessionOnly() throws Exception {
        long otherSessionId = IDS.incrementAndGet();
        extraSessionIds.add(otherSessionId);
        tx(() -> {
            jdbc.update("""
                    INSERT INTO session(id, tenant_code, session_no, channel_id, status, start_time)
                    VALUES (?, 'TENANT_A', ?, 900001, 2, CURRENT_TIMESTAMP)
                    """, otherSessionId, "OTHER_ANON_" + otherSessionId);
            jdbc.update("""
                    INSERT INTO session_message(id, tenant_code, session_id, msg_no, seq, msg_type, sender_type, content, visible_to, send_time)
                    VALUES (?, 'TENANT_A', ?, ?, 1, 1, 1, 'INTERNAL-SECRET-OTHER-SESSION', 1, CURRENT_TIMESTAMP)
                    """, IDS.incrementAndGet(), otherSessionId, "M_" + otherSessionId);
            return null;
        });

        var profile = success(profilePath);
        assertEquals("customer-profile-v1", profile.path("schema_version").textValue());
        assertFalse(profile.path("bound").booleanValue());
        assertTrue(profile.path("customer_no").isNull());
        assertTrue(profile.path("masked_name").isNull());
        assertTrue(profile.path("masked_phone").isNull());
        assertTrue(profile.path("points").isNull());
        assertEquals(profile, success(profilePath));

        var history = success(historyPath);
        assertEquals("session-history-v1", history.path("schema_version").textValue());
        assertFalse(history.path("bound").booleanValue());
        assertEquals(1, history.path("sessions").size());
        assertEquals(sessionNo, history.path("sessions").get(0).path("session_no").textValue());
    }

    @Test
    void boundCustomerProfileRedactsPiiExcludesRemarkAndTracksResourceVersion() throws Exception {
        bindCustomer();
        var profile = success(profilePath);
        assertTrue(profile.path("bound").booleanValue());
        assertEquals("CUS_" + customerId, profile.path("customer_no").textValue());
        assertEquals("张*", profile.path("masked_name").textValue());
        assertEquals("138****8888", profile.path("masked_phone").textValue());
        assertEquals(3, profile.path("level").intValue());
        assertEquals(2, profile.path("customer_type").intValue());
        assertEquals(1280, profile.path("points").intValue());
        assertEquals(12, profile.path("orders_count").intValue());
        assertEquals(4, profile.path("ticket_count").intValue());
        assertEquals(9, profile.path("session_count").intValue());
        assertEquals(2, profile.path("risk_level").intValue());
        assertFalse(profile.has("remark"));
        assertFalse(profile.has("phone"));
        assertFalse(profile.has("name"));

        String canonical = String.join("\n",
                "supportflow-customer-profile:v1",
                "TENANT_A",
                event.eventId().toString(),
                run.toString(),
                profile.path("snapshot_id").textValue(),
                profile.path("source_hash").textValue(),
                "true",
                "CUS_" + customerId,
                "张*",
                "138****8888",
                "3", "2", "1280", "12", "4", "9", "2");
        assertEquals(sha256(canonical), profile.path("resource_version").textValue());

        admin("UPDATE customer SET points = 2500 WHERE id = " + customerId);
        var updated = success(profilePath);
        assertEquals(2500, updated.path("points").intValue());
        assertNotEquals(profile.path("resource_version"), updated.path("resource_version"));
    }

    @Test
    void sessionHistoryLimitsToFiveSessionsThreeVisibleTextMessagesTruncatedTo240AndExcludesInternalNotes() throws Exception {
        bindCustomer();
        tx(() -> {
            for (int i = 1; i <= 5; i++) {
                long histId = IDS.incrementAndGet();
                extraSessionIds.add(histId);
                jdbc.update("""
                        INSERT INTO session(id, tenant_code, session_no, channel_id, customer_id, status, start_time)
                        VALUES (?, 'TENANT_A', ?, 900001, ?, 4, TIMESTAMP '2026-11-01 10:00:00' + (? * INTERVAL '1 hour'))
                        """, histId, "HIST_" + i + "_" + histId, customerId, i);
                for (int seq = 1; seq <= 4; seq++) {
                    String content = seq == 4 ? "长".repeat(280) : ("可见消息-" + i + "-" + seq);
                    jdbc.update("""
                            INSERT INTO session_message(id, tenant_code, session_id, msg_no, seq, msg_type, sender_type, content, visible_to, send_time)
                            VALUES (?, 'TENANT_A', ?, ?, ?, 1, 1, ?, 1, TIMESTAMP '2026-11-01 10:05:00' + (? * INTERVAL '1 second'))
                            """, IDS.incrementAndGet(), histId, "MSG_" + histId + "_" + seq, seq, content, seq);
                }
                // Internal staff note (visible_to = 2) and non-text image (msg_type = 2) MUST NEVER be returned.
                jdbc.update("""
                        INSERT INTO session_message(id, tenant_code, session_id, msg_no, seq, msg_type, sender_type, content, visible_to, send_time)
                        VALUES (?, 'TENANT_A', ?, ?, 5, 1, 2, 'INTERNAL-SECRET-STAFF-NOTE', 2, TIMESTAMP '2026-11-01 10:09:00')
                        """, IDS.incrementAndGet(), histId, "MSG_NOTE_" + histId);
                jdbc.update("""
                        INSERT INTO session_message(id, tenant_code, session_id, msg_no, seq, msg_type, sender_type, content, visible_to, send_time)
                        VALUES (?, 'TENANT_A', ?, ?, 6, 2, 1, 'INTERNAL-SECRET-IMAGE-URL', 1, TIMESTAMP '2026-11-01 10:10:00')
                        """, IDS.incrementAndGet(), histId, "MSG_IMG_" + histId);
            }
            // Foreign customer session must never appear.
            long foreignSession = IDS.incrementAndGet();
            extraSessionIds.add(foreignSession);
            jdbc.update("""
                    INSERT INTO session(id, tenant_code, session_no, channel_id, customer_id, status, start_time)
                    VALUES (?, 'TENANT_A', ?, 900001, ?, 2, CURRENT_TIMESTAMP + INTERVAL '1 day')
                    """, foreignSession, "FOREIGN_" + foreignSession, foreignCustomerId);
            return null;
        });

        var history = success(historyPath);
        assertTrue(history.path("bound").booleanValue());
        JsonNode sessions = history.path("sessions");
        assertEquals(5, sessions.size());
        for (JsonNode item : sessions) {
            assertFalse(item.path("session_no").textValue().startsWith("FOREIGN_"));
            JsonNode msgs = item.path("messages");
            assertTrue(msgs.size() <= 3);
            for (JsonNode msg : msgs) {
                assertTrue(msg.path("content").textValue().length() <= 240);
                assertFalse(msg.path("content").textValue().contains("INTERNAL-SECRET"));
            }
        }
        // Check a historical session's 3-message window (seqs 2, 3, 4; seq 1 dropped by LIMIT 3, seq 4 truncated to 240).
        JsonNode recentHist = sessions.get(1);
        assertEquals(3, recentHist.path("messages").size());
        assertEquals(2L, recentHist.path("messages").get(0).path("seq").longValue());
        assertEquals(4L, recentHist.path("messages").get(2).path("seq").longValue());
        assertEquals(240, recentHist.path("messages").get(2).path("content").textValue().length());
    }

    @Test
    void deletedAnonymizedMergedCustomerAndRevokedSourceAreRejectedOnBothRoutes() throws Exception {
        bindCustomer();
        success(profilePath);
        success(historyPath);

        for (String fault : new String[]{"is_deleted=true", "anonymized_at=CURRENT_TIMESTAMP", "merged_into='OTHER'"}) {
            admin("UPDATE customer SET " + fault + " WHERE id=" + customerId);
            denied(profilePath, 404, 40407);
            denied(historyPath, 404, 40408);
            admin("UPDATE customer SET is_deleted=false, anonymized_at=NULL, merged_into=NULL WHERE id=" + customerId);
        }

        for (String fault : new String[]{"agent_id=999", "status=4", "is_deleted=true"}) {
            admin("UPDATE session SET " + fault + " WHERE id=" + sessionId);
            denied(profilePath, 404, 40402);
            denied(historyPath, 404, 40402);
            admin("UPDATE session SET agent_id=NULL, status=2, is_deleted=false WHERE id=" + sessionId);
        }

        var foreignTenant = get(profilePath, "TENANT_B");
        assertEquals(404, foreignTenant.getResponse().getStatus());
        assertEquals(40402, body(foreignTenant).path("code").intValue());

        var queries = new AgentCustomerQueries(jdbc);
        var historyQueries = new AgentSessionHistoryQueries(jdbc);
        assertThrows(IllegalStateException.class, () -> queries.read("TENANT_A", event.eventId(), run));
        assertThrows(IllegalStateException.class, () -> historyQueries.read("TENANT_A", event.eventId(), run));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
