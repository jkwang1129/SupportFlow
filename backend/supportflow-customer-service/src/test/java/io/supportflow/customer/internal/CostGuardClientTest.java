package io.supportflow.customer.internal;

import io.supportflow.customer.dto.cost.Guard;
import io.supportflow.customer.service.metrics.BillingUsageCollector;
import io.supportflow.customer.testkit.RuntimeReceiverStub;
import io.supportflow.customer.testkit.RuntimeReceiverStub.Reply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CostGuardClientTest {
    private static final String TENANT = "TENANT_A";
    private final AtomicLong ticker = new AtomicLong();

    @Test
    void refreshesAtFiveSecondsAndKeepsLastPauseUntilAConfirmedReplacement() throws Exception {
        var response = new AtomicReference<>(guard(1));
        try (var ops = new RuntimeReceiverStub(request -> {
            assertEquals("GET", request.method());
            assertEquals("/api/ops/internal/observability/cost-guard", request.rawPath());
            assertEquals("tenantCode=TENANT_A", request.rawQuery());
            return response.get();
        })) {
            var client = client(ops);
            assertEquals(Guard.MODE_NORMAL, client.check(TENANT).mode());
            response.set(guard(3));
            tick(4999);
            assertEquals(Guard.MODE_NORMAL, client.check(TENANT).mode());
            assertEquals(1, ops.calls.get());
            tick(1);
            assertTrue(client.check(TENANT).isBlocked());
            response.set(new Reply(503, "{}"));
            tick(5000);
            assertTrue(client.check(TENANT).isBlocked());
            response.set(guard(1));
            tick(999);
            assertTrue(client.check(TENANT).isBlocked());
            assertEquals(3, ops.calls.get());
            tick(1);
            assertEquals(Guard.MODE_NORMAL, client.check(TENANT).mode());
            assertEquals(4, ops.calls.get());
            assertNull(ops.failure.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{",
            "{}",
            "{\"code\":50300,\"data\":{\"mode\":1,\"reason\":\"\"}}",
            "{\"code\":0,\"data\":{\"mode\":0,\"reason\":\"\"}}",
            "{\"code\":0,\"data\":{\"mode\":4,\"reason\":\"\"}}",
            "{\"code\":0,\"data\":{\"mode\":1.5,\"reason\":\"\"}}",
            "{\"code\":0,\"data\":{\"mode\":\"1\",\"reason\":\"\"}}",
            "{\"code\":0,\"data\":{\"mode\":1}}"
    })
    void rejectsUnconfirmedNormalResponses(String body) throws Exception {
        try (var ops = new RuntimeReceiverStub(request -> new Reply(200, body))) {
            assertTrue(client(ops).check(TENANT).isDegraded());
            assertEquals(1, ops.calls.get());
        }
    }

    @Test
    void invalidTenantNeverReadsOps() throws Exception {
        try (var ops = new RuntimeReceiverStub(request -> guard(1))) {
            var client = client(ops);
            assertTrue(client.check(null).isDegraded());
            assertTrue(client.check(" ").isDegraded());
            assertTrue(client.check("PLATFORM").isDegraded());
            assertTrue(client.check("TENANT+A").isDegraded());
            assertEquals(0, ops.calls.get());
        }
    }

    @Test
    void readTimeoutDegradesAndThrottlesRefresh() throws Exception {
        try (var ops = new RuntimeReceiverStub(request -> new Reply(
                200, guard(1).body(), "application/json", 8000, null))) {
            var client = client(ops);
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertTrue(client.check(TENANT).isDegraded()));
            assertTrue(client.check(TENANT).isDegraded());
            assertEquals(1, ops.calls.get());
        }
    }

    @Test
    void parallelLookupsShareOneRefreshPerTenant() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var ops = new RuntimeReceiverStub(request -> {
            if ("tenantCode=TENANT_B".equals(request.rawQuery())) {
                return guard(2);
            }
            assertEquals("tenantCode=TENANT_A", request.rawQuery());
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return guard(3);
        }); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var client = client(ops);
            var first = executor.submit(() -> client.check(TENANT));
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS));
                var duplicate = executor.submit(() -> client.check(TENANT));
                assertTrue(executor.submit(() -> client.check("TENANT_B")).get(2, TimeUnit.SECONDS).isDegraded());
                release.countDown();
                assertTrue(first.get(3, TimeUnit.SECONDS).isBlocked());
                assertTrue(duplicate.get(3, TimeUnit.SECONDS).isBlocked());
                assertEquals(2, ops.calls.get());
                assertNull(ops.failure.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void legacyBotSkipsModelAndBillingOnUnavailableAndResumesAfterRecovery() throws Exception {
        var response = new AtomicReference<>(new Reply(503, "{}"));
        var billing = mock(BillingUsageCollector.class);
        try (var ops = new RuntimeReceiverStub(request -> response.get());
             var ai = new RuntimeReceiverStub(request -> new Reply(200, "{\"reply\":\"fixture-reply\"}"))) {
            var bot = new BotAiClient("test-only-bot-signing-key-123456789", baseUrl(ai), false, 5, billing,
                    client(ops));
            assertEquals("cost-degrade", bot.think(TENANT, "S1", List.of(), 5).get("engine"));
            response.set(guard(3));
            tick(1000);
            assertEquals(true, bot.think(TENANT, "S1", List.of(), 5).get("need_human"));
            assertEquals(0, ai.calls.get());
            verifyNoInteractions(billing);

            response.set(guard(1));
            tick(5000);
            assertEquals("fixture-reply", bot.think(TENANT, "S1", List.of(), 5).get("reply"));
            assertEquals(1, ai.calls.get());
            verify(billing).countAiCall(TENANT);
        }
    }

    private void tick(long millis) {
        ticker.addAndGet(Duration.ofMillis(millis).toNanos());
    }

    private CostGuardClient client(RuntimeReceiverStub ops) {
        return new CostGuardClient(baseUrl(ops), ticker::get);
    }

    private static String baseUrl(RuntimeReceiverStub receiver) {
        return receiver.endpoint().resolve("/").toString();
    }

    private static Reply guard(int mode) {
        return new Reply(200, "{\"code\":0,\"data\":{\"mode\":" + mode + ",\"reason\":\"fixture\"}}");
    }
}
