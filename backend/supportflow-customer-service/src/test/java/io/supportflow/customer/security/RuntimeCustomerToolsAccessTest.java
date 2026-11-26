package io.supportflow.customer.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportflow.common.auth.CustomerInternalCredential;
import io.supportflow.customer.controller.internal.RuntimeCustomerProfileController;
import io.supportflow.customer.controller.internal.RuntimeSessionHistoryController;
import io.supportflow.customer.service.bot.AgentCustomerQueries;
import io.supportflow.customer.service.bot.AgentSessionHistoryQueries;
import io.supportflow.customer.testkit.RuntimeSourceWire;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import org.springframework.http.HttpMethod;

class RuntimeCustomerToolsAccessTest {
    private static final long NOW = 1_700_000_000L;
    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SNAPSHOT_ID = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final String PROFILE_PATH = RuntimeSourceWire.PATH.replace("snapshot", "customer-profile");
    private static final String HISTORY_PATH = RuntimeSourceWire.PATH.replace("snapshot", "session-history");
    private static final ObjectMapper JSON = new ObjectMapper();

    private RuntimeSourceAccessFilter filter(boolean source, boolean result, boolean read) {
        return new RuntimeSourceAccessFilter(
                RuntimeSourceWire.KEY, source, result, read, List.of(),
                Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
    }

    private MockHttpServletRequest signed(String method, String path, String body) {
        return signedForTenant(method, path, "TENANT_A", body);
    }

    private MockHttpServletRequest signedForTenant(String method, String path, String tenant, String body) {
        var request = new MockHttpServletRequest(method, path);
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        request.setContent(bytes);
        request.addHeader("X-Tenant-Code", tenant);
        request.addHeader(CustomerInternalCredential.HEADER,
                RuntimeSourceWire.credential(method, path, tenant, bytes, NOW + 20));
        return request;
    }

    private int rejected(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        filter(true, true, true).doFilter(request, response,
                (a, b) -> fail("Invalid runtime customer-tools call reached controller"));
        assertNull(request.getAttribute(RuntimeCustomerCall.ATTRIBUTE));
        return response.getStatus();
    }

    @Test
    void profileAndHistoryRoutesRequireReadToolsSwitchAndBindTenantCall() throws Exception {
        for (String target : List.of(PROFILE_PATH, HISTORY_PATH)) {
            var req = signed("GET", target, "");
            var called = new AtomicBoolean();
            filter(false, false, true).doFilter(req, new MockHttpServletResponse(), (a, b) -> {
                var call = (RuntimeCustomerCall) a.getAttribute(RuntimeCustomerCall.ATTRIBUTE);
                assertNotNull(call);
                assertEquals("TENANT_A", call.tenant());
                assertEquals(EVENT_ID, call.eventId());
                assertEquals(RUN_ID, call.runId());
                called.set(true);
            });
            assertTrue(called.get());

            var disabledReq = new MockHttpServletRequest("GET", target) {
                @Override
                public jakarta.servlet.ServletInputStream getInputStream() {
                    throw new AssertionError("Disabled read route must not consume body");
                }
            };
            disabledReq.addHeader("X-Tenant-Code", "TENANT_A");
            disabledReq.addHeader(CustomerInternalCredential.HEADER,
                    RuntimeSourceWire.credential("GET", target, "TENANT_A", new byte[0], NOW + 20));
            var disabledResp = new MockHttpServletResponse();
            filter(true, true, false).doFilter(disabledResp != null ? disabledReq : req, disabledResp, (a, b) -> fail());
            assertEquals(503, disabledResp.getStatus());
            assertEquals("5", disabledResp.getHeader("Retry-After"));
        }
    }

    @Test
    void nonGetMethodsQueryStringsBodiesPlatformTenantAndSubpathsAreRejected() throws Exception {
        for (String target : List.of(PROFILE_PATH, HISTORY_PATH)) {
            for (String method : List.of("POST", "PUT", "DELETE", "PATCH", "HEAD")) {
                assertEquals(403, rejected(signed(method, target, "")));
            }
            assertEquals(400, rejected(signed("GET", target, "{}")));
            assertEquals(413, rejected(signed("GET", target, "x".repeat(8193))));
            assertEquals(403, rejected(signedForTenant("GET", target, "PLATFORM", "")));
            assertEquals(403, rejected(signed("GET", target + "/extra", "")));

            var withQuery = signed("GET", target + "?limit=3", "");
            withQuery.setRequestURI(target);
            withQuery.setQueryString("limit=3");
            assertEquals(403, rejected(withQuery));

            assertEquals(401, rejected(new MockHttpServletRequest("GET", target)));
            var crossRoute = signed("GET", PROFILE_PATH, "");
            crossRoute.setRequestURI(HISTORY_PATH);
            assertEquals(401, rejected(crossRoute));
        }
    }

    @Test
    void piiMaskingAndCodePointTruncationPreserveStrictPrivacyInvariants() throws Exception {
        var maskName = AgentCustomerQueries.class.getDeclaredMethod("maskName", String.class);
        maskName.setAccessible(true);
        assertNull(maskName.invoke(null, (Object) null));
        assertNull(maskName.invoke(null, "   "));
        assertEquals("张*", maskName.invoke(null, "张三"));
        assertEquals("欧*", maskName.invoke(null, "欧阳修"));
        assertEquals("A*", maskName.invoke(null, "Alice Cooper"));

        var maskPhone = AgentCustomerQueries.class.getDeclaredMethod("maskPhone", String.class);
        maskPhone.setAccessible(true);
        assertNull(maskPhone.invoke(null, (Object) null));
        assertNull(maskPhone.invoke(null, ""));
        assertEquals("138****0000", maskPhone.invoke(null, "13812340000"));
        assertEquals("138****0000", maskPhone.invoke(null, "+86-138-1234-0000"));
        assertEquals("****", maskPhone.invoke(null, "PRIVATE-RAW-SECRET"));

        var truncate = AgentSessionHistoryQueries.class.getDeclaredMethod("truncateCodePoints", String.class, int.class);
        truncate.setAccessible(true);
        String longText = "客".repeat(300);
        String cut = (String) truncate.invoke(null, longText, 240);
        assertEquals(240, cut.codePointCount(0, cut.length()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void controllersSerializeBoundAndAnonymousProjectionsWithCanonicalDigestAndMaskDatabaseErrors() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager txManager = new ActiveStubTransactionManager();

        String unboundPayload = "{\"schema_version\":\"source-v1\",\"trusted_source\":{\"customer_ref\":null,\"event_seq\":1,\"message_id\":\"10\",\"principal_ref\":\"visitor-session:99\",\"run_id\":\"" + RUN_ID + "\",\"session_no\":\"S_99\",\"source_event_id\":\"" + EVENT_ID + "\",\"tenant_code\":\"TENANT_A\"},\"untrusted_input\":{\"content\":\"查档案\",\"role\":\"user\"}}";
        String unboundHash = sha256(unboundPayload);

        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            RowMapper<Object> mapper = invocation.getArgument(1, RowMapper.class);
            ResultSet rs = mock(ResultSet.class);
            if (sql.contains("FROM agent_event_outbox e") && sql.contains("content_bytes")) {
                when(rs.getString("session_no")).thenReturn("S_99");
                when(rs.getLong("message_id")).thenReturn(10L);
                when(rs.getLong("event_seq")).thenReturn(1L);
                when(rs.getString("principal_ref")).thenReturn("visitor-session:99");
                when(rs.getString("customer_id")).thenReturn(null);
                when(rs.getString("content")).thenReturn("查档案");
                when(rs.getObject("content_bytes")).thenReturn(9);
                return List.of(mapper.mapRow(rs, 0));
            }
            if (sql.contains("FROM agent_source_snapshot")) {
                when(rs.getString("tenant_code")).thenReturn("TENANT_A");
                when(rs.getObject("event_id", UUID.class)).thenReturn(EVENT_ID);
                when(rs.getObject("run_id", UUID.class)).thenReturn(RUN_ID);
                when(rs.getObject("snapshot_id", UUID.class)).thenReturn(SNAPSHOT_ID);
                when(rs.getString("schema_version")).thenReturn("source-v1");
                when(rs.getString("payload_json")).thenReturn(unboundPayload);
                when(rs.getString("payload_hash")).thenReturn(unboundHash);
                when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.ofEpochSecond(NOW)));
                when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(Instant.ofEpochSecond(NOW + 600)));
                when(rs.getBoolean("unexpired")).thenReturn(true);
                return List.of(mapper.mapRow(rs, 0));
            }
            if (sql.contains("JOIN session s ON s.id = e.session_id")) {
                when(rs.getLong("id")).thenReturn(99L);
                when(rs.getString("session_no")).thenReturn("S_99");
                when(rs.getObject("status")).thenReturn(2);
                when(rs.getTimestamp("started_at")).thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 11, 20, 10, 0, 0)));
                when(rs.getTimestamp("ended_at")).thenReturn(null);
                return List.of(mapper.mapRow(rs, 0));
            }
            if (sql.contains("FROM session_message m")) {
                when(rs.getObject("seq")).thenReturn(2L, 1L);
                when(rs.getObject("sender_type")).thenReturn(3, 1);
                when(rs.getString("content")).thenReturn("机器人回复", "访客提问");
                when(rs.getTimestamp("sent_at")).thenReturn(
                        Timestamp.valueOf(LocalDateTime.of(2026, 11, 20, 10, 0, 5)),
                        Timestamp.valueOf(LocalDateTime.of(2026, 11, 20, 10, 0, 1)));
                return List.of(mapper.mapRow(rs, 0), mapper.mapRow(rs, 1));
            }
            throw new AssertionError("Unexpected SQL: " + sql);
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new RuntimeCustomerProfileController(jdbc, txManager),
                        new RuntimeSessionHistoryController(jdbc, txManager))
                .addFilters(filter(false, false, true))
                .build();

        var profileResp = mvc.perform(request(HttpMethod.GET, URI.create(PROFILE_PATH))
                        .header("X-Tenant-Code", "TENANT_A")
                        .header(CustomerInternalCredential.HEADER,
                                RuntimeSourceWire.credential("GET", PROFILE_PATH, "TENANT_A", new byte[0], NOW + 20)))
                .andReturn().getResponse();
        assertEquals(200, profileResp.getStatus());
        JsonNode profileData = JSON.readTree(profileResp.getContentAsString()).path("data");
        assertEquals("customer-profile-v1", profileData.path("schema_version").textValue());
        assertFalse(profileData.path("bound").booleanValue());
        assertTrue(profileData.path("customer_no").isNull());
        assertTrue(profileData.path("masked_phone").isNull());
        String expectedProfileCanonical = String.join("\n",
                "supportflow-customer-profile:v1", "TENANT_A", EVENT_ID.toString(), RUN_ID.toString(),
                SNAPSHOT_ID.toString(), unboundHash, "false", "", "", "", "", "", "", "", "", "", "");
        assertEquals(sha256(expectedProfileCanonical), profileData.path("resource_version").textValue());

        var historyResp = mvc.perform(request(HttpMethod.GET, URI.create(HISTORY_PATH))
                        .header("X-Tenant-Code", "TENANT_A")
                        .header(CustomerInternalCredential.HEADER,
                                RuntimeSourceWire.credential("GET", HISTORY_PATH, "TENANT_A", new byte[0], NOW + 20)))
                .andReturn().getResponse();
        assertEquals(200, historyResp.getStatus());
        JsonNode historyData = JSON.readTree(historyResp.getContentAsString()).path("data");
        assertEquals("session-history-v1", historyData.path("schema_version").textValue());
        assertFalse(historyData.path("bound").booleanValue());
        assertEquals(1, historyData.path("sessions").size());
        JsonNode messages = historyData.path("sessions").get(0).path("messages");
        assertEquals(2, messages.size());
        assertEquals(1L, messages.get(0).path("seq").longValue());
        assertEquals("访客提问", messages.get(0).path("content").textValue());
        assertEquals(2L, messages.get(1).path("seq").longValue());

        doThrow(new QueryTimeoutException("SELECT secret_column FROM customer")).when(jdbc).execute(anyString());
        var errorResp = mvc.perform(request(HttpMethod.GET, URI.create(PROFILE_PATH))
                        .header("X-Tenant-Code", "TENANT_A")
                        .header(CustomerInternalCredential.HEADER,
                                RuntimeSourceWire.credential("GET", PROFILE_PATH, "TENANT_A", new byte[0], NOW + 20)))
                .andReturn().getResponse();
        assertEquals(503, errorResp.getStatus());
        assertFalse(errorResp.getContentAsString().contains("secret_column"));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class ActiveStubTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        @Override
        public void rollback(TransactionStatus status) {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
