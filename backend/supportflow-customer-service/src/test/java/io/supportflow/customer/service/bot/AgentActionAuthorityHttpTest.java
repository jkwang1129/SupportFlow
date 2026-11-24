package io.supportflow.customer.service.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real Java client -> real AI HTTP authentication/store -> restricted PG17. */
@EnabledIfEnvironmentVariable(named="SUPPORTFLOW_ACTION_TEST_RUN", matches=".+")
class AgentActionAuthorityHttpTest {
    @Test void enabledTicketAuthorityCannotBeUsedForReplyAndCancellationRevokesNewPermits()throws Exception{
        var endpoint=URI.create(System.getenv("SUPPORTFLOW_RUNTIME_DELIVERY_URL"));
        var ticket=new AgentActionPermit.Request("AUTHORITY_T",UUID.fromString(System.getenv("SUPPORTFLOW_ACTION_TEST_TICKET_RUN")),
            UUID.fromString(System.getenv("SUPPORTFLOW_ACTION_TEST_TICKET_EVENT")),"authority-ticket-worker",1,
            UUID.fromString(System.getenv("SUPPORTFLOW_ACTION_TEST_TICKET_SNAPSHOT")),"a".repeat(64),AgentActionPermit.Operation.TICKET_CREATE);
        var reply=new AgentActionPermit.Request(ticket.tenant(),ticket.runId(),ticket.eventId(),ticket.owner(),ticket.fence(),ticket.snapshotId(),ticket.argumentsSha256());
        String secret=System.getenv("SUPPORTFLOW_RUNTIME_CUSTOMER_TEST_SECRET");
        try(var client=new AgentRuntimeClient(endpoint,secret);var http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()){
            var permit=client.authorizeTicketCreate(ticket);assertEquals(ticket,permit.request());assertEquals("ticket-1",permit.stepKey());
            assertFalse(permit.requiresApproval());
            assertEquals("AUTHORITY_HTTP_409",assertThrows(AgentRuntimeClient.DeliveryFailure.class,
                    ()->client.authorizeTicketCreate(ticket,true)).code());
            assertEquals("AUTHORITY_HTTP_409",assertThrows(AgentRuntimeClient.DeliveryFailure.class,()->client.authorizeReply(reply)).code());
            assertThrows(IllegalArgumentException.class,()->client.authorizeReply(ticket));
            var cancel=URI.create(endpoint+"/"+ticket.runId()+"/cancel");
            assertEquals(200,http.send(AgentRuntimeProtocol.request(cancel,"POST",ticket.tenant(),"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),secret),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals("AUTHORITY_HTTP_409",assertThrows(AgentRuntimeClient.DeliveryFailure.class,()->client.authorizeTicketCreate(ticket)).code());
        }
    }
    @Test void currentWriteIntentGrantsAuthorityButWrongIdentityOrCancellationCannot() throws Exception {
        var endpoint = URI.create(System.getenv("SUPPORTFLOW_RUNTIME_DELIVERY_URL"));
        assertEquals("127.0.0.1", endpoint.getHost()); assertEquals("http", endpoint.getScheme());
        var action = new AgentActionPermit.Request("AUTHORITY_A", UUID.fromString(System.getenv("SUPPORTFLOW_ACTION_TEST_RUN")),
                UUID.fromString(System.getenv("SUPPORTFLOW_ACTION_TEST_EVENT")), "authority-worker", 1,
                UUID.fromString(System.getenv("SUPPORTFLOW_ACTION_TEST_SNAPSHOT")), "a".repeat(64));
        String secret = System.getenv("SUPPORTFLOW_RUNTIME_CUSTOMER_TEST_SECRET");
        try (var client = new AgentRuntimeClient(endpoint, secret);
             var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var permit = client.authorizeReply(action);
            assertEquals(action, permit.request()); assertEquals("reply-1", permit.stepKey()); assertEquals(1, permit.stepNo());
            assertTrue(permit.expiresAt().isAfter(Instant.now())); permit.requireValidAt(Instant.now());
            var wrongTenant = new AgentActionPermit.Request("AUTHORITY_B", action.runId(), action.eventId(), action.owner(), action.fence(), action.snapshotId(), action.argumentsSha256());
            var wrongFence = new AgentActionPermit.Request(action.tenant(), action.runId(), action.eventId(), action.owner(), 99, action.snapshotId(), action.argumentsSha256());
            var wrongHash = new AgentActionPermit.Request(action.tenant(), action.runId(), action.eventId(), action.owner(), 1, action.snapshotId(), "b".repeat(64));
            assertEquals("AUTHORITY_HTTP_404", assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> client.authorizeReply(wrongTenant)).code());
            assertEquals("AUTHORITY_HTTP_409", assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> client.authorizeReply(wrongFence)).code());
            assertEquals("AUTHORITY_HTTP_409", assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> client.authorizeReply(wrongHash)).code());
            try (var wrongKey = new AgentRuntimeClient(endpoint, secret + "wrong")) {
                assertEquals("AUTHORITY_HTTP_401", assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> wrongKey.authorizeReply(action)).code());
            }
            var cancel = URI.create(endpoint + "/" + action.runId() + "/cancel");
            var response = http.send(AgentRuntimeProtocol.request(cancel, "POST", action.tenant(), "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), secret), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("SIDE_EFFECT_UNKNOWN"));
            var denied = assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> client.authorizeReply(action));
            assertEquals("AUTHORITY_HTTP_409", denied.code()); assertFalse(denied.retryable());
            // No business message is written: previously issued in-flight permits
            // are short-lived, not instantaneously revocable or proof of execution.
        }
    }
}
