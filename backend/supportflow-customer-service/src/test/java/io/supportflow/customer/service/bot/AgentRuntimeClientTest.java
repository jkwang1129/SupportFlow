package io.supportflow.customer.service.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportflow.customer.testkit.RuntimeReceiverStub;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeClientTest {
    static final String KEY = "runtime-test-only-customer-0000000000000000000000000";
    static final ObjectMapper JSON = new ObjectMapper();
    static AgentEventOutbox.Event event() {
        return new AgentEventOutbox.Event("TENANT_A", UUID.randomUUID(), 42, "S1", 1, "visitor-session:7", "trace-1", "runtime-v1", "SENDING", 1, 1, "worker", null, null);
    }
    static String receipt(AgentEventOutbox.Event event, UUID run) {
        return "{\"tenant_code\":\"" + event.tenant() + "\",\"source_event_id\":\"" + event.eventId() + "\",\"run_id\":\"" + run + "\",\"state\":\"PENDING\"}";
    }

    @Test void actualHttpSignsTheExactReferenceBodyAndNeverSendsUserCredentials() throws Exception {
        var event = event(); var run = UUID.randomUUID();
        try (var receiver = new RuntimeReceiverStub(request -> {
            assertEquals("POST", request.method());
            assertEquals("/api/ai/v1/runtime/runs", request.rawPath());
            assertNull(request.headers().get("Authorization"));
            assertEquals(java.util.List.of(event.traceId()), request.headers().get("X-Request-Id"));
            var json = JSON.readTree(request.body());
            assertEquals(8, json.size());
            assertEquals(event.eventId().toString(), json.get("source_event_id").textValue());
            assertEquals(event.principalRef(), json.get("principal_ref").textValue());
            String signed = request.headers().get("X-SupportFlow-AI-Auth").getFirst();
            long expiry = Long.parseLong(signed.split(":")[2]);
            assertEquals(signed, AgentRuntimeProtocol.credential(KEY, expiry, request.method(), request.rawPath(), event.tenant(), request.body()));
            return new RuntimeReceiverStub.Reply(202, receipt(event, run));
        }); var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            assertEquals(new AgentRuntimeClient.Receipt(event.eventId(), run), client.enqueue(event));
            assertEquals(1, receiver.calls.get()); assertNull(receiver.failure.get());
        }
    }

    @Test void httpErrorsAreClassifiedWithoutRetryingOrLeakingTheirBody() throws Exception {
        for (int code : new int[]{400, 401, 403, 404, 409, 413, 415, 422, 408, 425, 429, 500, 503}) {
            try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(code, "synthetic-private-error"));
                 var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
                var failure = assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> client.enqueue(event()));
                assertEquals(code == 408 || code == 425 || code == 429 || code >= 500, failure.retryable());
                assertEquals("RECEIVER_HTTP_" + code, failure.code());
                assertFalse(failure.toString().contains("synthetic-private-error"));
                assertNull(failure.getCause()); assertEquals(1, receiver.calls.get());
            }
        }
    }

    @Test void malformedForeignDuplicateAndNoncanonicalReceiptsCannotAcknowledge() throws Exception {
        var event = event(); String good = receipt(event, UUID.randomUUID());
        for (String body : new String[]{"[]", "not-json", good + "{}", good.replace("TENANT_A", "TENANT_B"),
                good.replace(event.eventId().toString(), UUID.randomUUID().toString()),
                good.replace("PENDING", "WAITING_FOR_MAGIC"), good.replace("PENDING", "RUNNING\",\"state\":\"PENDING"),
                good.replace("\"run_id\":\"", "\"run_id\":\"UPPER"), good.replace("\"PENDING\"", "0")}) {
            try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(202, body));
                 var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
                var failure = assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> client.enqueue(event));
                assertEquals("INVALID_RECEIPT", failure.code()); assertFalse(failure.retryable());
            }
        }
    }

    @Test void responseSizeAndContentTypeAreEnforced() throws Exception {
        for (var reply : new RuntimeReceiverStub.Reply[]{new RuntimeReceiverStub.Reply(202, "x".repeat(8193)),
                new RuntimeReceiverStub.Reply(202, "{}", "text/plain", 0, null)}) {
            try (var receiver = new RuntimeReceiverStub(request -> reply);
                 var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
                var failure = assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> client.enqueue(event()));
                assertFalse(failure.retryable());
                assertEquals(reply.body().length() > 8192 ? "RESPONSE_TOO_LARGE" : "INVALID_RECEIPT", failure.code());
            }
        }
    }

    @Test void unavailableReceiverIsTransientAndInvalidConfigurationFailsBeforeDelivery() throws Exception {
        var closed = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(202, "{}"));
        var endpoint = closed.endpoint(); closed.close();
        try (var client = new AgentRuntimeClient(endpoint, KEY, Duration.ofSeconds(2))) {
            var failure = assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> client.enqueue(event()));
            assertTrue(failure.retryable());
            assertTrue(java.util.Set.of("DELIVERY_NETWORK_ERROR", "DELIVERY_TIMEOUT").contains(failure.code()));
        }
        assertThrows(IllegalArgumentException.class, () -> new AgentRuntimeClient(endpoint, KEY, Duration.ofSeconds(11)));
        assertThrows(IllegalArgumentException.class, () -> new AgentRuntimeClient(endpoint.resolve(endpoint.getPath() + "/00000000-0000-0000-0000-000000000001/cancel"), KEY));
    }

    @Test void wholeExchangeDeadlineIncludesAStalledBodyAfterResponseHeaders() throws Exception {
        for (boolean delayBody : new boolean[]{false, true}) {
            var event = event();
            try (var receiver = new RuntimeReceiverStub(request -> {
                if (!delayBody) Thread.sleep(2500);
                return new RuntimeReceiverStub.Reply(202, receipt(event, UUID.randomUUID()), "application/json", delayBody ? 2500 : 0, null);
            }); var client = new AgentRuntimeClient(receiver.endpoint(), KEY, Duration.ofMillis(500))) {
                long start = System.nanoTime();
                var failure = assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> client.enqueue(event));
                assertTrue(failure.retryable()); assertEquals("DELIVERY_TIMEOUT", failure.code());
                assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000, "Local fault must stop before the injected delay");
            }
        }
    }

    @Test void redirectsAreNotFollowedAndInterruptionPreservesTheStopSignal() throws Exception {
        try (var target = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(202, "{}"));
             var redirect = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(302, "{}", "application/json", 0, target.endpoint().toString()));
             var client = new AgentRuntimeClient(redirect.endpoint(), KEY)) {
            assertFalse(assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> client.enqueue(event())).retryable());
            assertEquals(0, target.calls.get());
        }
        var entered = new CountDownLatch(1); var keptInterrupt = new AtomicBoolean();
        try (var receiver = new RuntimeReceiverStub(request -> { entered.countDown(); Thread.sleep(3000); return new RuntimeReceiverStub.Reply(202, "{}"); });
             var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            Thread thread = new Thread(() -> {
                var failure = assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> client.enqueue(event()));
                assertEquals("DELIVERY_INTERRUPTED", failure.code()); keptInterrupt.set(Thread.currentThread().isInterrupted());
            });
            thread.start(); assertTrue(entered.await(5, TimeUnit.SECONDS)); thread.interrupt(); thread.join(2000);
            assertFalse(thread.isAlive()); assertTrue(keptInterrupt.get());
        }
    }
}
