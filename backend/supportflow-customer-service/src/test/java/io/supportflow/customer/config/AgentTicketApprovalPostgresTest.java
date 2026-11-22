package io.supportflow.customer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportflow.common.auth.AuthState;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.context.LoginUserContext;
import io.supportflow.customer.controller.ticket.AgentTicketApprovalController;
import io.supportflow.customer.internal.UserRoleClient;
import io.supportflow.customer.service.bot.AgentEventOutbox;
import io.supportflow.customer.service.bot.AgentRuntimeClient;
import io.supportflow.customer.service.bot.AgentSourceSnapshots;
import io.supportflow.customer.service.bot.AgentTicketArguments;
import io.supportflow.customer.testkit.RuntimeReceiverStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@EnabledIfEnvironmentVariable(named = "SUPPORTFLOW_RLS_TEST_URL", matches = ".+")
class AgentTicketApprovalPostgresTest extends SessionPostgresFixture {
    private static final String KEY = "synthetic-approval-bridge-key-000000000000000000000";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long UID = 9007199254740993L;
    private static final LoginUser ADMIN = new LoginUser(UID, "U_1", "Fixture reviewer", 2, "TENANT_A");
    private final UUID run = UUID.randomUUID();
    private final UUID approval = UUID.randomUUID();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicBoolean takeoverDuringPreview = new AtomicBoolean();
    private final AtomicBoolean unmatchedActiveReference = new AtomicBoolean();
    private final AtomicBoolean unmatchedProgressReference = new AtomicBoolean();
    private static final String PROPOSAL_TITLE = "合成提案🙂";
    private static final String PROPOSAL_BODY = "原始内容\n ";
    private UUID event;
    private String path;
    private UserRoleClient roles;
    private RuntimeReceiverStub server;
    private AgentRuntimeClient client;
    private MockMvc mvc;

    @BeforeEach void setupReview() throws Exception {
        var snapshot = scoped(() -> transaction.execute(s -> {
            var message = service.appendMessage("TENANT_A", sessionNo, 1, null, 1, "synthetic approval source", 1, "approval-source");
            var outbox = new AgentEventOutbox(jdbc);
            event = outbox.enqueue("TENANT_A", Long.parseLong(message.msgId()), "runtime-v1", 500).eventId();
            outbox.acknowledge(outbox.claim("TENANT_A", "approval-fixture", 30), event, run);
            return new AgentSourceSnapshots(jdbc).capture("TENANT_A", event, run);
        }));
        path = "/api/customer/agent-approvals/events/" + event + "/runs/" + run + "/decisions";
        roles = mock(UserRoleClient.class);
        when(roles.rolesOf(UID, "TENANT_A")).thenReturn(List.of("ADMIN"));
        when(roles.rolesOf(UID, "TENANT_B")).thenReturn(List.of("ADMIN"));
        server = new RuntimeReceiverStub(request -> {
            assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
            assertEquals(List.of("TENANT_A"), request.headers().get("X-Tenant-Code"));
            assertNull(request.headers().get("Authorization"));
            if (request.method().equals("GET")) {
                assertTrue(request.rawPath().endsWith("/runs/" + run));
                assertEquals(0, request.body().length);
                if (status.get() != 200) return new RuntimeReceiverStub.Reply(status.get(), "synthetic private upstream failure");
                if (takeoverDuringPreview.get()) scoped(() -> jdbc.update("UPDATE session SET agent_id=99 WHERE id=?", sessionId));
                return new RuntimeReceiverStub.Reply(200, JSON.writeValueAsString(Map.of(
                        "tenant_code", "TENANT_A", "run_id", run.toString(), "source_event_id",
                        (unmatchedProgressReference.get() ? UUID.randomUUID() : event).toString(),
                        "state", "FAILED", "attempt", 2, "step_count", 6, "model_calls", 0, "tool_calls", 4,
                        "retry_count", 1, "error_code", "FIXTURE_PRIVATE_FAILURE")));
            }
            var node = JSON.readTree(request.body());
            if (request.rawPath().endsWith("/ticket-approval-active")) {
                assertEquals(JSON.valueToTree(Map.of("session_no", sessionNo)), node);
                if (status.get() != 200) return new RuntimeReceiverStub.Reply(status.get(), "synthetic private upstream failure");
                UUID activeEvent = unmatchedActiveReference.get() ? UUID.randomUUID() : event;
                return new RuntimeReceiverStub.Reply(200, JSON.writeValueAsString(Map.of("source_event_id", activeEvent.toString(),
                        "run_id", run.toString(), "status", "PENDING", "expires_at", Instant.now().plusSeconds(60).toString())));
            }
            assertEquals(event.toString(), node.get("source_event_id").textValue());
            if (request.rawPath().endsWith("/ticket-approval-preview")) {
                assertEquals(1, node.size());
                if (status.get() != 200) return new RuntimeReceiverStub.Reply(status.get(), "synthetic private upstream failure");
                if (takeoverDuringPreview.get()) scoped(() -> jdbc.update("UPDATE session SET agent_id=99 WHERE id=?", sessionId));
                var arguments = new AgentTicketArguments("TENANT_A", run, event, snapshot.snapshotId(), PROPOSAL_TITLE, PROPOSAL_BODY);
                return new RuntimeReceiverStub.Reply(200, JSON.writeValueAsString(Map.of("approval_id", approval.toString(),
                        "title", PROPOSAL_TITLE, "content", PROPOSAL_BODY, "arguments_sha256", arguments.argumentsSha256(),
                        "expires_at", Instant.now().plusSeconds(60).toString(), "status", "PENDING")));
            }
            assertEquals("staff:" + UID, node.get("reviewer_ref").textValue());
            if (status.get() != 200) return new RuntimeReceiverStub.Reply(status.get(), "synthetic private upstream failure");
            String result = switch (node.get("decision").textValue()) { case "approve" -> "APPROVED"; case "deny" -> "DENIED"; default -> "REVOKED"; };
            return new RuntimeReceiverStub.Reply(200, JSON.writeValueAsString(Map.of("approval_id", approval.toString(), "status", result)));
        });
        client = new AgentRuntimeClient(server.endpoint(), KEY);
        mvc = mvc(client);
    }

    private MockMvc mvc(AgentRuntimeClient runtime) {
        return MockMvcBuilders.standaloneSetup(new AgentTicketApprovalController(jdbc, transaction.getTransactionManager(), roles, runtime)).build();
    }

    private String body(String decision) throws Exception {
        return JSON.writeValueAsString(Map.of("approval_id", approval.toString(), "arguments_sha256", "a".repeat(64), "decision", decision));
    }

    private MvcResult call(MockMvc target, LoginUser user, String route, String payload) throws Exception {
        return LoginUserContext.bind(user == null ? AuthState.anonymous() : AuthState.ok(user), () ->
                target.perform(post(route).header("X-Tenant-Code", "FORGED").contentType("application/json").content(payload)).andReturn());
    }

    private MvcResult preview(LoginUser user) throws Exception {
        return LoginUserContext.bind(user == null ? AuthState.anonymous() : AuthState.ok(user), () ->
                mvc.perform(get(path.replace("/decisions", "/proposal")).header("X-Tenant-Code", "FORGED")).andReturn());
    }

    private MvcResult active(LoginUser user) throws Exception {
        return LoginUserContext.bind(user == null ? AuthState.anonymous() : AuthState.ok(user), () ->
                mvc.perform(get("/api/customer/agent-approvals/sessions/" + sessionNo + "/active")
                        .header("X-Tenant-Code", "FORGED")).andReturn());
    }

    private MvcResult progress(LoginUser user) throws Exception {
        return LoginUserContext.bind(user == null ? AuthState.anonymous() : AuthState.ok(user), () ->
                mvc.perform(get(path.replace("/decisions", "/progress")).header("X-Tenant-Code", "FORGED")).andReturn());
    }

    @AfterEach @Override void cleanup() {
        try {
            if (client != null) client.close();
            if (server != null) server.close();
            if (event != null) {
                try (var connection = DriverManager.getConnection(System.getenv("SUPPORTFLOW_RLS_TEST_URL"), "supportflow",
                        System.getenv("SUPPORTFLOW_RLS_TEST_MIGRATION_PASSWORD"))) {
                    for (String table : List.of("agent_source_snapshot", "agent_event_outbox")) {
                        try (var delete = connection.prepareStatement("DELETE FROM " + table + " WHERE event_id=?")) {
                            delete.setObject(1, event);
                            delete.executeUpdate();
                        }
                    }
                } catch (Exception failure) { throw new IllegalStateException("Disposable review fixture cleanup failed", failure); }
            }
        } finally { super.cleanup(); }
    }

    @Test void authenticAdminOrSupervisorReviewUsesCurrentSourceBeforeSendingOnlyReferences() throws Exception {
        assertEquals(200, call(mvc, ADMIN, path, body("approve")).getResponse().getStatus());
        when(roles.rolesOf(UID, "TENANT_A")).thenReturn(List.of("SUPERVISOR"));
        assertEquals(200, call(mvc, ADMIN, path, body("deny")).getResponse().getStatus());
        assertEquals(2, server.calls.get());
        assertNull(server.failure.get());
    }

    @Test void anonymousPlatformUnknownOldAndReadOnlyRolesCannotCallApprovalService() throws Exception {
        assertEquals(401, call(mvc, null, path, body("approve")).getResponse().getStatus());
        assertEquals(403, call(mvc, new LoginUser(UID, "P_1", "platform", 1, "PLATFORM"), path, body("approve")).getResponse().getStatus());
        for (List<String> denied : java.util.Arrays.asList(List.<String>of(), List.of("AGENT"), List.of("QUALITY"), null)) {
            when(roles.rolesOf(UID, "TENANT_A")).thenReturn(denied);
            assertEquals(403, call(mvc, ADMIN, path, body("approve")).getResponse().getStatus());
        }
        assertEquals(0, server.calls.get());
    }

    @Test void otherTenantAndWrongEventOrRunAreDeniedBeforeReviewHttp() throws Exception {
        assertEquals(404, call(mvc, new LoginUser(UID, "U_1", "fixture", 2, "TENANT_B"), path, body("approve")).getResponse().getStatus());
        assertEquals(404, call(mvc, ADMIN, path.replace(event.toString(), UUID.randomUUID().toString()), body("deny")).getResponse().getStatus());
        assertEquals(404, call(mvc, ADMIN, path.replace(run.toString(), UUID.randomUUID().toString()), body("revoke")).getResponse().getStatus());
        assertEquals(0, server.calls.get());
    }

    @Test void takeoverStopsApprovalButStillAllowsNarrowingDecision() throws Exception {
        scoped(() -> jdbc.update("UPDATE session SET agent_id=99 WHERE id=?", sessionId));
        assertEquals(404, call(mvc, ADMIN, path, body("approve")).getResponse().getStatus());
        assertEquals(200, call(mvc, ADMIN, path, body("revoke")).getResponse().getStatus());
        assertEquals(1, server.calls.get());
    }

    @Test void disabledMalformedForgedOrOversizedInputIsRejectedBeforeRuntimeReview() throws Exception {
        assertEquals(503, call(mvc(null), ADMIN, path, body("approve")).getResponse().getStatus());
        String good = body("approve");
        for (String invalid : List.of(good + "{}", good.replace("\"approve\"", "\"approve\",\"decision\":\"deny\""),
                good.substring(0, good.length()-1) + ",\"reviewer_id\":1}", body("consume"))) {
            assertEquals(422, call(mvc, ADMIN, path, invalid).getResponse().getStatus());
        }
        assertEquals(413, call(mvc, ADMIN, path, " ".repeat(2049)).getResponse().getStatus());
        assertEquals(0, server.calls.get());
    }

    @Test void upstreamStatusMapsToStableConflictAndUnavailableWithoutLeakingItsBody() throws Exception {
        for (int value : new int[]{409, 503, 401}) {
            status.set(value);
            var response = call(mvc, ADMIN, path, body("approve")).getResponse();
            assertEquals(value == 409 ? 409 : 503, response.getStatus());
            assertFalse(response.getContentAsString().contains("synthetic private"));
        }
        assertEquals(3, server.calls.get());
        assertNull(server.failure.get());
    }

    @Test void previewReturnsExactOriginalTermsWithoutCaching() throws Exception {
        var response = preview(ADMIN).getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        var terms = JSON.readTree(response.getContentAsByteArray()).get("data");
        assertEquals(6, terms.size());
        assertEquals(PROPOSAL_BODY, terms.get("content").textValue());
        assertEquals(PROPOSAL_TITLE, terms.get("title").textValue());
        assertEquals(approval.toString(), terms.get("approval_id").textValue());
        assertEquals(1, server.calls.get());
        assertNull(server.failure.get());
    }

    @Test void unauthorizedRolesAndTenantCannotPreviewTheTerms() throws Exception {
        assertEquals(401, preview(null).getResponse().getStatus());
        assertEquals(404, preview(new LoginUser(UID, "U_1", "other tenant", 2, "TENANT_B")).getResponse().getStatus());
        when(roles.rolesOf(UID, "TENANT_A")).thenReturn(List.of("AGENT"));
        assertEquals(403, preview(ADMIN).getResponse().getStatus());
        assertEquals(0, server.calls.get());
    }

    @Test void takeoverWhilePreviewIsInFlightPreventsDisclosure() throws Exception {
        takeoverDuringPreview.set(true);
        var response = preview(ADMIN).getResponse();
        assertEquals(404, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertFalse(response.getContentAsString().contains(PROPOSAL_BODY));
        assertEquals(1, server.calls.get());
        assertNull(server.failure.get());
    }

    @Test void progressReturnsOnlySafeFieldsForTheCurrentReviewer() throws Exception {
        var response = progress(ADMIN).getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals(JSON.valueToTree(Map.of("state", "FAILED", "attempt", 2, "step_count", 6)),
                JSON.readTree(response.getContentAsByteArray()).get("data"));
        assertFalse(response.getContentAsString().contains("FIXTURE_PRIVATE_FAILURE"));
        assertEquals(1, server.calls.get());
        assertNull(server.failure.get());
    }

    @Test void wrongTenantAndRoleAreRefusedBeforeProgressFetch() throws Exception {
        assertEquals(401, progress(null).getResponse().getStatus());
        assertEquals(404, progress(new LoginUser(UID, "U_1", "Other tenant", 2, "TENANT_B")).getResponse().getStatus());
        when(roles.rolesOf(UID, "TENANT_A")).thenReturn(List.of("AGENT"));
        assertEquals(403, progress(ADMIN).getResponse().getStatus());
        assertEquals(0, server.calls.get());
    }

    @Test void unmatchedResponseAndConcurrentTakeoverSuppressProgress() throws Exception {
        unmatchedProgressReference.set(true);
        assertEquals(503, progress(ADMIN).getResponse().getStatus());
        unmatchedProgressReference.set(false);
        takeoverDuringPreview.set(true);
        var response = progress(ADMIN).getResponse();
        assertEquals(404, response.getStatus());
        assertFalse(response.getContentAsString().contains("FAILED"));
        assertEquals(2, server.calls.get());
        assertNull(server.failure.get());
    }

    @Test void activeReferenceOnlyReturnsMetadataAndNoResultForAnEmptySession() throws Exception {
        var response = active(ADMIN).getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        var item = JSON.readTree(response.getContentAsByteArray()).get("data");
        assertEquals(4, item.size());
        assertEquals(event.toString(), item.get("source_event_id").textValue());
        assertEquals(run.toString(), item.get("run_id").textValue());
        assertEquals("PENDING", item.get("status").textValue());
        status.set(404);
        response = active(ADMIN).getResponse();
        assertEquals(200, response.getStatus());
        assertTrue(JSON.readTree(response.getContentAsByteArray()).path("data").isNull());
        assertNull(server.failure.get());
    }

    @Test void foreignOrdinaryAndTakenOverSessionsNeverCauseDiscoveryHttp() throws Exception {
        assertEquals(401, active(null).getResponse().getStatus());
        assertEquals(404, active(new LoginUser(UID, "U_1", "other tenant", 2, "TENANT_B")).getResponse().getStatus());
        when(roles.rolesOf(UID, "TENANT_A")).thenReturn(List.of("AGENT"));
        assertEquals(403, active(ADMIN).getResponse().getStatus());
        when(roles.rolesOf(UID, "TENANT_A")).thenReturn(List.of("SUPERVISOR"));
        scoped(() -> jdbc.update("UPDATE session SET agent_id=99 WHERE id=?", sessionId));
        assertEquals(404, active(ADMIN).getResponse().getStatus());
        assertEquals(0, server.calls.get());
    }

    @Test void unrelatedAiReferenceNeverReachesStaff() throws Exception {
        unmatchedActiveReference.set(true);
        var response = active(ADMIN).getResponse();
        assertEquals(404, response.getStatus());
        assertFalse(response.getContentAsString().contains(run.toString()));
        assertEquals(1, server.calls.get());
        assertNull(server.failure.get());
    }
}
