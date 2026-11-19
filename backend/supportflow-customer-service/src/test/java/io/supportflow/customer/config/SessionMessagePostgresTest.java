package io.supportflow.customer.config;

import io.supportflow.common.context.TenantContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.dto.session.MessageVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real production mappers + SessionService + Spring transaction synchronization, restricted PG role. */
@EnabledIfEnvironmentVariable(named = "SUPPORTFLOW_RLS_TEST_URL", matches = ".+")
class SessionMessagePostgresTest extends SessionPostgresFixture {
    @Test
    void concurrentDuplicateRequestsReturnOneCommittedMessageAndOneSetOfEffects() throws Exception {
        var bothReadAbsent = new CyclicBarrier(2);
        var initialReads = new AtomicInteger();
        doAnswer(call -> {
            var existing = realMessages.selectByClientMsgNo(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            if (initialReads.incrementAndGet() <= 2) bothReadAbsent.await(5, TimeUnit.SECONDS);
            return existing;
        }).when(messages).selectByClientMsgNo(eq("TENANT_A"), eq(sessionId), eq("same-event"));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> append("same-event"));
            var second = workers.submit(() -> append("same-event"));
            var a = first.get(15, TimeUnit.SECONDS);
            var b = second.get(15, TimeUnit.SECONDS);
            assertEquals(a.msgId(), b.msgId());
            assertEquals(1L, a.seq());
            assertEquals(a.seq(), b.seq());
        }
        assertFacts(1, 1);
        verify(billing, times(1)).countMessage("TENANT_A");
        verify(brain, times(1)).handleCustomerMessageAsync("TENANT_A", sessionNo);
        assertEquals(1, committedEvents.get());
    }

    @Test
    void rollbackDoesNotBillScheduleOrConsumeACommittedSequence() {
        scoped(() -> transaction.execute(status -> {
            service.appendMessage("TENANT_A", sessionNo, 1, null, 1, "synthetic", 1, "rollback");
            status.setRollbackOnly();
            return null;
        }));
        assertFacts(0, 0);
        verifyNoInteractions(billing);
        verify(brain, never()).handleCustomerMessageAsync(anyString(), anyString());
        assertEquals(0, committedEvents.get());
        assertEquals(1L, append("rollback").seq());
        assertFacts(1, 1);
    }

    @Test
    void differentEventsRemainContinuousAndRetryingAnExistingEventDoesNotAdvanceSequence() throws Exception {
        var start = new CountDownLatch(1);
        var results = new ArrayList<MessageVO>();
        try (var workers = Executors.newFixedThreadPool(6)) {
            List<Future<MessageVO>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String key = "unique-" + i;
                futures.add(workers.submit(() -> { start.await(); return append(key); }));
            }
            start.countDown();
            for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));
        }
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), results.stream().map(MessageVO::seq).sorted().toList());
        assertEquals(6L, results.stream().map(MessageVO::msgNo).distinct().count());
        assertEquals(results.getFirst().msgId(), append("unique-0").msgId());
        assertFacts(6, 6);
        verify(billing, times(6)).countMessage("TENANT_A");
        verify(brain, times(6)).handleCustomerMessageAsync("TENANT_A", sessionNo);
        assertEquals(6, committedEvents.get());
    }

    @Test
    void tombstonedIdempotencyKeyIsAControlledConflictWithoutAnAbortedTransaction() {
        append("deleted-event");
        scoped(() -> { jdbc.update("UPDATE session_message SET is_deleted=true WHERE session_id=?", sessionId); return null; });
        // The unique index intentionally reserves deleted client keys too. Do not reveal or reuse the row.
        var conflict = assertThrows(BizException.class, () -> append("deleted-event"));
        assertNotNull(conflict.getMessage());
        assertFacts(1, 1);
        assertEquals(2L, append("next-event").seq());
    }

    @Test
    void sessionClosedAfterTheInitialReadCannotReceiveANewMessage() {
        doAnswer(call -> {
            var existing = realMessages.selectByClientMsgNo(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            try (var closer = Executors.newSingleThreadExecutor()) {
                closer.submit(() -> scoped(() -> jdbc.update("UPDATE session SET status=4 WHERE id=?", sessionId)))
                        .get(5, TimeUnit.SECONDS);
            }
            return existing;
        }).when(messages).selectByClientMsgNo(eq("TENANT_A"), eq(sessionId), eq("closing"));
        assertThrows(BizException.class, () -> append("closing"));
        assertFacts(0, 0);
        verifyNoInteractions(billing);
        verify(brain, never()).handleCustomerMessageAsync(anyString(), anyString());
    }

    @Test
    void foreignTenantCannotAppendUsingAnotherTenantsSessionNumber() {
        assertThrows(BizException.class, () -> TenantContext.bind("TENANT_B", () -> transaction.execute(
                status -> service.appendMessage("TENANT_B", sessionNo, 1, null, 1, "foreign", 1, "foreign"))));
        assertFacts(0, 0);
        verifyNoInteractions(billing);
        verify(brain, never()).handleCustomerMessageAsync(anyString(), anyString());
    }

    @Test
    void messagesWithoutClientKeysRetainTheirExistingAppendSemantics() {
        var first = append(null);
        var second = append("  ");
        assertNotEquals(first.msgId(), second.msgId());
        assertEquals(1L, first.seq());
        assertEquals(2L, second.seq());
        assertFacts(2, 2);
    }

    private MessageVO append(String clientId) {
        return scoped(() -> transaction.execute(status -> service.appendMessage(
                "TENANT_A", sessionNo, 1, null, 1, "synthetic", 1, clientId)));
    }

    private void assertFacts(int rows, long lastSequence) {
        scoped(() -> {
            assertEquals(rows, jdbc.queryForObject("SELECT count(*) FROM session_message WHERE session_id=?", Integer.class, sessionId));
            assertEquals(lastSequence, jdbc.queryForObject("SELECT last_msg_seq FROM session WHERE id=?", Long.class, sessionId));
            return null;
        });
    }

}
