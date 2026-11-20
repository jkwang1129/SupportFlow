package io.supportflow.customer.service.bot;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeProtocolTest {
    static final String KEY = "runtime-test-only-customer-0000000000000000000000000";
    static final String PATH = "/api/ai/v1/runtime/runs";
    static final String BODY = "{\"tenant_code\":\"TENANT_A\",\"source_event_id\":\"00000000-0000-0000-0000-000000000001\",\"session_no\":\"S1\",\"event_seq\":1,\"message_id\":42,\"principal_ref\":\"visitor-session:7\",\"trace_id\":\"trace-1\",\"runtime_version\":\"runtime-v1\"}";

    @Test
    void matchesPythonWireFixtureAndEveryBoundFieldChangesTheCredential() {
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        String expected = "runtime:customer:1700000020:232f1a0e53d8be124043925590d6de8265117cb97939ccd5a5eb97baae9a3280";
        assertEquals(expected, AgentRuntimeProtocol.credential(KEY, 1700000020, "POST", PATH, "TENANT_A", body));
        assertNotEquals(expected, AgentRuntimeProtocol.credential(KEY, 1700000020, "POST", PATH, "TENANT_B", body));
        assertNotEquals(expected, AgentRuntimeProtocol.credential(KEY, 1700000020, "GET", PATH, "TENANT_A", body));
        assertNotEquals(expected, AgentRuntimeProtocol.credential(KEY, 1700000020, "POST", PATH + "/cancel", "TENANT_A", body));
        assertNotEquals(expected, AgentRuntimeProtocol.credential(KEY, 1700000021, "POST", PATH, "TENANT_A", body));
        assertNotEquals(expected, AgentRuntimeProtocol.credential(KEY, 1700000020, "POST", PATH, "TENANT_A", (BODY + " ").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void builderHasBoundedTimeoutExactBodyAndCannotForwardUntrustedHeaders() throws Exception {
        byte[] original = BODY.getBytes(StandardCharsets.UTF_8);
        var request = AgentRuntimeProtocol.request(URI.create("http://127.0.0.1:9100" + PATH), "POST", "TENANT_A", original, KEY);
        original[0] = '!';
        assertEquals(10, request.timeout().orElseThrow().toSeconds());
        assertEquals(BODY.getBytes(StandardCharsets.UTF_8).length, request.bodyPublisher().orElseThrow().contentLength());
        assertTrue(request.headers().firstValue("X-SupportFlow-AI-Auth").orElseThrow().startsWith("runtime:customer:"));
        assertFalse(request.headers().firstValue("Authorization").isPresent());
        var collected = new java.util.concurrent.CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(java.nio.ByteBuffer buffer) {
                byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); output.writeBytes(bytes);
            }
            public void onError(Throwable error) { collected.completeExceptionally(error); }
            public void onComplete() { collected.complete(output.toByteArray()); }
        });
        byte[] wire = collected.get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertArrayEquals(BODY.getBytes(StandardCharsets.UTF_8), wire);
        String credential = request.headers().firstValue("X-SupportFlow-AI-Auth").orElseThrow();
        long expires = Long.parseLong(credential.split(":")[2]);
        assertEquals(credential, AgentRuntimeProtocol.credential(KEY, expires, "POST", PATH, "TENANT_A", wire));
    }

    @Test
    void rejectsQueriesCredentialsInvalidOperationsTenantsAndExcessivePayloads() {
        for (String path : new String[]{PATH + "?tenant_code=TENANT_B", PATH + "#fragment", "/api/ai/v1/agent/brain", PATH.replace("runs", "%72uns")}) {
            assertThrows(IllegalArgumentException.class, () -> AgentRuntimeProtocol.request(URI.create("http://127.0.0.1" + path), "POST", "TENANT_A", new byte[0], KEY));
        }
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeProtocol.request(URI.create("http://user:password@localhost" + PATH), "POST", "TENANT_A", new byte[0], KEY));
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeProtocol.request(URI.create("http://localhost" + PATH), "POST", "PLATFORM", new byte[0], KEY));
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeProtocol.request(URI.create("http://localhost" + PATH), "POST", "TENANT_A", new byte[8193], KEY));
        assertThrows(IllegalStateException.class, () -> AgentRuntimeProtocol.request(URI.create("http://localhost" + PATH), "POST", "TENANT_A", new byte[0], "short"));
    }
}
