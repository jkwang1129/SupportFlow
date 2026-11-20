package io.supportflow.customer.service.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.supportflow.customer.testkit.RuntimeReceiverStub;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AgentActionPermitTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String KEY = AgentRuntimeClientTest.KEY;
    static final String FIXTURE = """
        {"version":"action-v1","tenant_code":"TENANT_A","run_id":"00000000-0000-0000-0000-000000000001",
         "source_event_id":"00000000-0000-0000-0000-000000000002","owner":"worker-1","fence":3,"step_key":"reply-1","step_no":2,
         "operation":"session.reply","snapshot_id":"00000000-0000-0000-0000-000000000003",
         "arguments_sha256":"dd804ecdbde66c0582160701a5f5cee9c461808bef8d9cdca2cfac0f04d63533","expires_at_ms":1700000004900,
         "signature":"93e4a92722d1651caebf0786db91cc7e4532660377ea0cdbbc1489a33af9ec18"}
        """;
    static AgentActionPermit.Request action() {
        return new AgentActionPermit.Request("TENANT_A", UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"), "worker-1", 3,
                UUID.fromString("00000000-0000-0000-0000-000000000003"), "dd804ecdbde66c0582160701a5f5cee9c461808bef8d9cdca2cfac0f04d63533");
    }
    static ObjectNode fixture() throws Exception { return (ObjectNode) JSON.readTree(FIXTURE); }
    static ObjectNode signed(long expires) throws Exception {
        var root = fixture().put("expires_at_ms", expires);
        var fields = new String[]{"version", "tenant_code", "run_id", "source_event_id", "owner", "fence", "step_key", "step_no",
                "operation", "snapshot_id", "arguments_sha256", "expires_at_ms"};
        var wire = new StringBuilder("supportflow-agent-action:v1");
        for (String field : fields) wire.append('\n').append(root.get(field).asText());
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return root.put("signature", HexFormat.of().formatHex(mac.doFinal(wire.toString().getBytes(StandardCharsets.UTF_8))));
    }
    static String fresh() throws Exception { return signed(Instant.now().plusSeconds(4).toEpochMilli()).toString(); }

    @Test void actualPythonSignatureAndDatabaseClockExpiryBoundaryAreVerified() throws Exception {
        var now = Instant.ofEpochSecond(1700000000);
        var permit = AgentActionPermit.verify(fixture(), action(), KEY, now);
        assertEquals(action(), permit.request()); assertEquals("reply-1", permit.stepKey()); assertEquals(2, permit.stepNo());
        assertEquals(Instant.ofEpochMilli(1700000004900L), permit.expiresAt());
        permit.requireValidAt(permit.expiresAt().minusMillis(1));
        assertThrows(IllegalStateException.class, () -> permit.requireValidAt(permit.expiresAt()));
        assertThrows(IllegalStateException.class, () -> permit.requireValidAt(now.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> AgentActionPermit.verify(fixture(), action(), KEY + "x", now));
    }

    @Test void everySignedFieldIsBoundAndForeignOrMalformedAuthorityIsRejected() throws Exception {
        var names = fixture().fieldNames();
        while (names.hasNext()) {
            String field = names.next(); var changed = fixture(); var value = changed.get(field);
            if (value.isNumber()) changed.put(field, value.longValue() + 1); else changed.put(field, value.textValue() + "x");
            assertThrows(RuntimeException.class, () -> AgentActionPermit.verify(changed, action(), KEY, Instant.ofEpochSecond(1700000000)), field);
        }
        for (var invalid : new ObjectNode[]{fixture().put("extra", "x"), fixture().put("fence", "3"), fixture().put("step_no", true),
                fixture().put("expires_at_ms", 1.7e12), fixture().put("step_no", 101), fixture().put("step_key", "../worker"),
                fixture().put("fence", new java.math.BigInteger("9223372036854775808"))}) {
            assertThrows(RuntimeException.class, () -> AgentActionPermit.verify(invalid, action(), KEY, Instant.ofEpochSecond(1700000000)));
        }
        var missing = fixture(); missing.remove("owner");
        assertThrows(IllegalArgumentException.class, () -> AgentActionPermit.verify(missing, action(), KEY, Instant.ofEpochSecond(1700000000)));
    }

    @Test void exactSignedHttpRequestUsesOnlyAuthorityReferencesAndReturnsVerifiedPermit() throws Exception {
        try (var receiver = new RuntimeReceiverStub(request -> {
            assertEquals("/api/ai/v1/runtime/runs/" + action().runId() + "/authorize", request.rawPath());
            assertEquals("POST", request.method()); assertNull(request.headers().get("Authorization"));
            var body = JSON.readTree(request.body()); assertEquals(8, body.size());
            assertEquals("session.reply", body.get("operation").textValue()); assertEquals("reply-v1", body.get("version").textValue());
            assertEquals(action().snapshotId().toString(), body.get("snapshot_id").textValue());
            String credential = request.headers().get("X-SupportFlow-AI-Auth").getFirst();
            long expiry = Long.parseLong(credential.split(":")[2]);
            assertEquals(credential, AgentRuntimeProtocol.credential(KEY, expiry, request.method(), request.rawPath(), "TENANT_A", request.body()));
            return new RuntimeReceiverStub.Reply(200, fresh());
        }); var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            assertEquals(action(), client.authorizeReply(action()).request());
            assertEquals(1, receiver.calls.get()); assertNull(receiver.failure.get());
        }
    }

    @Test void responseSchemaDuplicatesTrailingTokensAndExpiredPermitsAreRejected() throws Exception {
        for (String body : new String[]{"[]", "not-json", fresh() + "{}", fresh().replace("\"fence\":3", "\"fence\":3,\"fence\":3"),
                signed(Instant.now().minusSeconds(1).toEpochMilli()).toString(), signed(Instant.now().plusSeconds(60).toEpochMilli()).toString(),
                fixture().toString()}) {
            try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(200, body));
                 var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
                var failure = assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> client.authorizeReply(action()));
                assertFalse(failure.retryable()); assertEquals("INVALID_ACTION_PERMIT", failure.code());
            }
        }
    }

    @Test void authorityHttpErrorsNeverLeakOrImplicitlyRetryAndRedirectIsNotFollowed() throws Exception {
        for (int code : new int[]{401, 403, 404, 409, 422, 429, 500, 503}) {
            try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(code, "synthetic-private-error"));
                 var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
                var failure = assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> client.authorizeReply(action()));
                assertEquals(code >= 500 || code == 429, failure.retryable()); assertEquals("AUTHORITY_HTTP_" + code, failure.code());
                assertNull(failure.getCause()); assertEquals(1, receiver.calls.get());
            }
        }
        try (var target = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(200, fresh()));
             var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(302, "{}", "application/json", 0, target.endpoint().toString()));
             var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            assertFalse(assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> client.authorizeReply(action())).retryable());
            assertEquals(0, target.calls.get());
        }
    }

    @Test void overallDeadlineSizeAndContentTypeAlsoApplyToAuthority() throws Exception {
        for (var reply : new RuntimeReceiverStub.Reply[]{new RuntimeReceiverStub.Reply(200, "x".repeat(8193)),
                new RuntimeReceiverStub.Reply(200, fresh(), "text/plain", 0, null),
                new RuntimeReceiverStub.Reply(200, fresh(), "application/json", 2500, null)}) {
            try (var receiver = new RuntimeReceiverStub(request -> reply);
                 var client = new AgentRuntimeClient(receiver.endpoint(), KEY, Duration.ofMillis(500))) {
                long start = System.nanoTime();
                var failure = assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> client.authorizeReply(action()));
                assertEquals(reply.body().length() > 8192 ? "RESPONSE_TOO_LARGE" : reply.bodyDelayMillis() > 0 ? "AUTHORITY_TIMEOUT" : "INVALID_ACTION_PERMIT", failure.code());
                assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis() < 2000);
                assertEquals(1, receiver.calls.get());
            }
        }
    }

    @Test void activeBusinessTransactionCannotMakeAnAuthorityNetworkCall() throws Exception {
        try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(200, fresh()));
             var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            try {
                TransactionSynchronizationManager.setActualTransactionActive(true);
                assertThrows(IllegalStateException.class, () -> client.authorizeReply(action()));
                assertEquals(0, receiver.calls.get());
            } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        }
    }

    @Test void invalidAuthorityReferencesFailBeforeAnyRequest() {
        var a = action();
        for (String tenant : new String[]{"PLATFORM", "platform", "", "TENANT A"})
            assertThrows(IllegalArgumentException.class, () -> new AgentActionPermit.Request(tenant, a.runId(), a.eventId(), a.owner(), 1, a.snapshotId(), a.argumentsSha256()));
        assertThrows(IllegalArgumentException.class, () -> new AgentActionPermit.Request(a.tenant(), a.runId(), a.eventId(), "../worker", 1, a.snapshotId(), a.argumentsSha256()));
        assertThrows(IllegalArgumentException.class, () -> new AgentActionPermit.Request(a.tenant(), a.runId(), a.eventId(), a.owner(), 0, a.snapshotId(), a.argumentsSha256()));
        assertThrows(IllegalArgumentException.class, () -> new AgentActionPermit.Request(a.tenant(), a.runId(), a.eventId(), a.owner(), 1, a.snapshotId(), "A".repeat(64)));
    }
}
