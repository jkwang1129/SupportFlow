package io.supportflow.customer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportflow.common.context.TenantContext;
import io.supportflow.customer.service.bot.AgentEventDispatcher;
import io.supportflow.customer.service.bot.AgentEventOutbox;
import io.supportflow.customer.service.bot.AgentRuntimeClient;
import io.supportflow.customer.testkit.RuntimeReceiverStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static io.supportflow.customer.service.bot.AgentEventDispatcher.Result.*;

@EnabledIfEnvironmentVariable(named="SUPPORTFLOW_RLS_TEST_URL", matches=".+")
class AgentEventDispatcherPostgresTest extends SessionPostgresFixture {
    private static final String KEY = "runtime-test-only-customer-0000000000000000000000000";
    private static final ObjectMapper JSON = new ObjectMapper();
    private AgentEventOutbox outbox;
    private final java.util.List<Long> extraSessions = new java.util.ArrayList<>();

    @BeforeEach void createOutbox() { outbox = new AgentEventOutbox(jdbc); }
    private <T> T tx(Supplier<T> action) { return scoped(() -> transaction.execute(status -> action.get())); }
    private AgentEventOutbox.Event append(String key) { return append(sessionNo, key); }
    private AgentEventOutbox.Event append(String session, String key) {
        return tx(() -> {
            var message = service.appendMessage("TENANT_A", session, 1, null, 1, "synthetic-private-message", 1, key);
            return outbox.enqueue("TENANT_A", Long.parseLong(message.msgId()), "runtime-v1", 500);
        });
    }
    private AgentEventOutbox.Event stored(AgentEventOutbox.Event event) { return tx(() -> outbox.getByMessage("TENANT_A", event.messageId())); }
    private AgentEventDispatcher dispatcher(AgentRuntimeClient client) { return new AgentEventDispatcher(outbox, transaction.getTransactionManager(), client); }
    private static String receipt(RuntimeReceiverStub.Request request, UUID run) throws Exception {
        var input = JSON.readTree(request.body());
        return JSON.writeValueAsString(java.util.Map.of("tenant_code", "TENANT_A", "source_event_id", input.get("source_event_id").textValue(), "run_id", run.toString(), "state", "PENDING"));
    }
    private void due(AgentEventOutbox.Event event) {
        admin("UPDATE agent_event_outbox SET ready_at=clock_timestamp()-interval '1 second' WHERE event_id='" + event.eventId() + "'");
    }
    private void expire(AgentEventOutbox.Event event) {
        admin("UPDATE agent_event_outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE event_id='" + event.eventId() + "'");
    }
    private void admin(String sql) {
        try (var connection = DriverManager.getConnection(System.getenv("SUPPORTFLOW_RLS_TEST_URL"), "supportflow",
                System.getenv("SUPPORTFLOW_RLS_TEST_MIGRATION_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception failure) { throw new IllegalStateException("Disposable fixture administration failed", failure); }
    }
    @Override @AfterEach void cleanup() {
        try {
            admin("DELETE FROM agent_event_outbox WHERE session_id=" + sessionId);
            for (long id : extraSessions) admin("DELETE FROM agent_event_outbox WHERE session_id=" + id + "; DELETE FROM session_message WHERE session_id=" + id + "; DELETE FROM session WHERE id=" + id);
        } finally { super.cleanup(); }
    }

    @Test void networkRunsAfterClaimCommitWithoutHoldingPoolConnectionsOrRowLocks() throws Exception {
        var event = append("network-outside-tx"); UUID run = UUID.randomUUID();
        try (var receiver = new RuntimeReceiverStub(request -> {
            assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
            admin("BEGIN; SET LOCAL lock_timeout='200ms'; SELECT event_id FROM agent_event_outbox WHERE event_id='" + event.eventId() + "' FOR UPDATE NOWAIT; COMMIT");
            assertFalse(new String(request.body(), java.nio.charset.StandardCharsets.UTF_8).contains("synthetic-private-message"));
            return new RuntimeReceiverStub.Reply(202, receipt(request, run));
        }); var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            var dispatcher = dispatcher(client);
            assertEquals(DELIVERED, dispatcher.dispatchOne("TENANT_A"));
            assertEquals(run, stored(event).runId()); assertEquals("DELIVERED", stored(event).state());
            assertEquals(IDLE, dispatcher.dispatchOne("TENANT_A"));
            assertEquals(1, receiver.calls.get()); assertNull(receiver.failure.get());
        }
    }

    @Test void activeBusinessTransactionInvalidTenantAndInterruptionDoNotClaim() throws Exception {
        var event = append("no-claim");
        try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(500, "{}"));
             var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            var dispatcher = dispatcher(client);
            assertThrows(IllegalStateException.class, () -> tx(() -> dispatcher.dispatchOne("TENANT_A")));
            assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatchOne("PLATFORM"));
            assertThrows(IllegalArgumentException.class, () -> TenantContext.bind("TENANT_B", () -> dispatcher.dispatchOne("TENANT_A")));
            try {
                Thread.currentThread().interrupt();
                assertEquals(INTERRUPTED, dispatcher.dispatchOne("TENANT_A"));
            } finally { Thread.interrupted(); }
            assertEquals(0, stored(event).attempts()); assertEquals(0, receiver.calls.get());
        }
    }

    @Test void transientFailureKeepsSourceOrderAndOnlyOneSendPerAttempt() throws Exception {
        var first = append("first"); var second = append("second"); var sends = new AtomicInteger();
        try (var receiver = new RuntimeReceiverStub(request -> sends.incrementAndGet() == 1
                ? new RuntimeReceiverStub.Reply(503, "synthetic-private-error")
                : new RuntimeReceiverStub.Reply(202, receipt(request, UUID.randomUUID())));
             var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            var dispatcher = dispatcher(client);
            assertEquals(RETRY, dispatcher.dispatchOne("TENANT_A"));
            assertEquals("PENDING", stored(first).state()); assertEquals(1, stored(first).attempts());
            assertEquals(0, stored(second).attempts());
            due(first);
            assertEquals(DELIVERED, dispatcher.dispatchOne("TENANT_A"));
            assertEquals(DELIVERED, dispatcher.dispatchOne("TENANT_A"));
            assertEquals(3, receiver.calls.get()); assertNull(receiver.failure.get());
        }
    }

    @Test void stopAfterClaimCommitDoesNotStartANewHttpRequest() throws Exception {
        var event = append("stop-before-network"); var commits = new AtomicInteger();
        var delegate = transaction.getTransactionManager();
        PlatformTransactionManager manager = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return delegate.getTransaction(definition); }
            public void rollback(TransactionStatus status) { delegate.rollback(status); }
            public void commit(TransactionStatus status) {
                delegate.commit(status);
                if (commits.incrementAndGet() == 1) Thread.currentThread().interrupt();
            }
        };
        try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(500, "{}"));
             var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            var dispatcher = new AgentEventDispatcher(outbox, manager, client);
            try {
                assertEquals(INTERRUPTED, dispatcher.dispatchOne("TENANT_A"));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            assertEquals("SENDING", stored(event).state()); assertEquals(1, stored(event).attempts());
            assertEquals(0, receiver.calls.get());
        }
    }

    @Test void permanentFailureHoldsSessionButAnIndependentSessionCanProgress() throws Exception {
        var first = append("conflicting"); var second = append("blocked");
        long otherId = IDS.incrementAndGet(); extraSessions.add(otherId); String other = "RELIABILITY_" + otherId;
        tx(() -> { jdbc.update("INSERT INTO session(id,tenant_code,session_no,channel_id,status,start_time) VALUES(?,'TENANT_A',?,900001,2,CURRENT_TIMESTAMP)", otherId, other); return null; });
        var independent = append(other, "independent");
        try (var receiver = new RuntimeReceiverStub(request -> {
            var id = UUID.fromString(JSON.readTree(request.body()).get("source_event_id").textValue());
            return id.equals(first.eventId()) ? new RuntimeReceiverStub.Reply(409, "private-conflict")
                    : new RuntimeReceiverStub.Reply(202, receipt(request, UUID.randomUUID()));
        }); var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            var dispatcher = dispatcher(client);
            assertEquals(HELD, dispatcher.dispatchOne("TENANT_A"));
            assertEquals("HELD", stored(first).state());
            assertEquals(DELIVERED, dispatcher.dispatchOne("TENANT_A"));
            assertEquals("DELIVERED", stored(independent).state()); assertEquals(0, stored(second).attempts());
            assertEquals(IDLE, dispatcher.dispatchOne("TENANT_A")); assertEquals(2, receiver.calls.get());
        }
    }

    @Test void eightTransientFailuresExhaustDeliveryBudgetWithoutDroppingTheEvent() throws Exception {
        var event = append("exhausted"); append("blocked");
        try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(503, "synthetic-private-error"));
             var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            var dispatcher = dispatcher(client);
            for (int attempt = 1; attempt <= 8; attempt++) {
                assertEquals(attempt < 8 ? RETRY : HELD, dispatcher.dispatchOne("TENANT_A"));
                assertEquals(attempt, stored(event).attempts()); due(event);
            }
            assertEquals("HELD", stored(event).state());
            assertEquals("RECEIVER_HTTP_503", tx(() -> jdbc.queryForObject("SELECT error_code FROM agent_event_outbox WHERE event_id=?", String.class, event.eventId())));
            assertEquals(IDLE, dispatcher.dispatchOne("TENANT_A")); assertEquals(8, receiver.calls.get());
        }
    }

    @Test void lostResponseResendsSamePersistedEventAndUsesOriginalAcceptedRun() throws Exception {
        var event = append("lost-response");
        var accepted = new ConcurrentHashMap<UUID, UUID>(); var sends = new AtomicInteger();
        try (var receiver = new RuntimeReceiverStub(request -> {
            UUID id = UUID.fromString(JSON.readTree(request.body()).get("source_event_id").textValue());
            UUID run = accepted.computeIfAbsent(id, ignored -> UUID.randomUUID());
            return new RuntimeReceiverStub.Reply(202, receipt(request, run), "application/json", sends.incrementAndGet() == 1 ? 2500 : 0, null);
        }); var client = new AgentRuntimeClient(receiver.endpoint(), KEY, Duration.ofMillis(500))) {
            assertEquals(RETRY, dispatcher(client).dispatchOne("TENANT_A"));
            due(event);
            assertEquals(DELIVERED, dispatcher(client).dispatchOne("TENANT_A"));
            assertEquals(1, accepted.size()); assertEquals(2, stored(event).attempts());
            assertEquals(accepted.get(event.eventId()), stored(event).runId()); assertEquals(2, receiver.calls.get());
        }
    }

    @Test void invalidReceiptCannotAcknowledgeOrReleaseLaterMessages() throws Exception {
        var event = append("bad-receipt"); var later = append("blocked-by-receipt");
        try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(202,
                receipt(request, UUID.randomUUID()).replace("TENANT_A", "TENANT_B")));
             var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            var dispatcher = dispatcher(client);
            assertEquals(HELD, dispatcher.dispatchOne("TENANT_A"));
            assertEquals("HELD", stored(event).state()); assertNull(stored(event).runId());
            assertEquals(IDLE, dispatcher.dispatchOne("TENANT_A")); assertEquals(0, stored(later).attempts());
            assertEquals(1, receiver.calls.get());
        }
    }

    @Test void interruptionDuringHttpLeavesTheOriginalLeaseForLaterTakeover() throws Exception {
        var event = append("interrupted"); UUID run = UUID.randomUUID();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var outcome = new CompletableFuture<AgentEventDispatcher.Result>(); var interrupted = new AtomicBoolean();
        try (var receiver = new RuntimeReceiverStub(request -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }
            return new RuntimeReceiverStub.Reply(202, receipt(request, run));
        }); var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            var dispatcher = dispatcher(client);
            Thread thread = new Thread(() -> {
                try {
                    var result = dispatcher.dispatchOne("TENANT_A");
                    interrupted.set(Thread.currentThread().isInterrupted()); outcome.complete(result);
                } catch (Throwable error) { outcome.completeExceptionally(error); }
            });
            thread.start();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); thread.interrupt();
                assertEquals(INTERRUPTED, outcome.get(3, TimeUnit.SECONDS));
                thread.join(1000); assertFalse(thread.isAlive()); assertTrue(interrupted.get());
                assertEquals("SENDING", stored(event).state()); assertEquals(1, stored(event).attempts());
                assertNull(stored(event).runId());
            } finally { release.countDown(); }
            expire(event);
            assertEquals(DELIVERED, dispatcher(client).dispatchOne("TENANT_A"));
            assertEquals(run, stored(event).runId()); assertEquals(2, stored(event).attempts());
        }
    }

    @Test void lateSuccessCannotAcknowledgeAfterAnotherDispatcherHasTakenOver() throws Exception { lateResponse(false); }
    @Test void lateFailureCannotScheduleRetryAfterAnotherDispatcherHasTakenOver() throws Exception { lateResponse(true); }
    private void lateResponse(boolean firstFails) throws Exception {
        var event = append("late-receipt"); UUID run = UUID.randomUUID();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        try (var receiver = new RuntimeReceiverStub(request -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
                if (firstFails) return new RuntimeReceiverStub.Reply(503, "{}");
            }
            return new RuntimeReceiverStub.Reply(202, receipt(request, run));
        }); var client = new AgentRuntimeClient(receiver.endpoint(), KEY); var executor = Executors.newSingleThreadExecutor()) {
            var first = dispatcher(client);
            var response = executor.submit(() -> first.dispatchOne("TENANT_A"));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(BUSY, first.dispatchOne("TENANT_A"));
                assertEquals(1, stored(event).attempts());
                expire(event);
                assertEquals(DELIVERED, dispatcher(client).dispatchOne("TENANT_A"));
            } finally { release.countDown(); }
            assertEquals(STALE, response.get(5, TimeUnit.SECONDS));
            assertEquals("DELIVERED", stored(event).state()); assertEquals(run, stored(event).runId());
            assertEquals(2, stored(event).attempts()); assertNull(receiver.failure.get());
        }
    }

    @Test void acknowledgementTransactionRollbackLeavesOriginalEventForRedelivery() throws Exception {
        var event = append("ack-rollback"); UUID run = UUID.randomUUID(); var fail = new AtomicBoolean(true);
        var faulty = new AgentEventOutbox(jdbc) {
            @Override public void acknowledge(Event lease, UUID accepted, UUID runId) {
                super.acknowledge(lease, accepted, runId);
                if (fail.getAndSet(false)) throw new org.springframework.dao.DataAccessResourceFailureException("synthetic-ack-rollback");
            }
        };
        try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(202, receipt(request, run)));
             var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            var dispatcher = new AgentEventDispatcher(faulty, transaction.getTransactionManager(), client);
            assertThrows(org.springframework.dao.DataAccessException.class, () -> dispatcher.dispatchOne("TENANT_A"));
            assertEquals("SENDING", stored(event).state()); assertNull(stored(event).runId());
            expire(event);
            assertEquals(DELIVERED, dispatcher.dispatchOne("TENANT_A"));
            assertEquals(run, stored(event).runId()); assertEquals(2, receiver.calls.get());
        }
    }

    @Test void commitOutcomeErrorAfterRealCommitDoesNotCauseAnotherSend() throws Exception {
        var event = append("unknown-commit"); UUID run = UUID.randomUUID(); var commits = new AtomicInteger();
        var delegate = transaction.getTransactionManager();
        PlatformTransactionManager manager = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return delegate.getTransaction(definition); }
            public void rollback(TransactionStatus status) { delegate.rollback(status); }
            public void commit(TransactionStatus status) {
                delegate.commit(status);
                if (commits.incrementAndGet() == 2) throw new org.springframework.transaction.TransactionSystemException("synthetic lost commit response");
            }
        };
        try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(202, receipt(request, run)));
             var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            var dispatcher = new AgentEventDispatcher(outbox, manager, client);
            assertThrows(org.springframework.transaction.TransactionSystemException.class, () -> dispatcher.dispatchOne("TENANT_A"));
            assertEquals("DELIVERED", stored(event).state()); assertEquals(run, stored(event).runId());
            assertEquals(IDLE, dispatcher.dispatchOne("TENANT_A")); assertEquals(1, receiver.calls.get());
        }
    }
}
