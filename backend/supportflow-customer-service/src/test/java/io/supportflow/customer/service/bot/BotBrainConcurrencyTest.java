package io.supportflow.customer.service.bot;

import io.supportflow.common.context.TenantContext;
import io.supportflow.customer.entity.Session;
import io.supportflow.customer.internal.BotAiClient;
import io.supportflow.customer.internal.RealtimeNotifyClient;
import io.supportflow.customer.service.session.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BotBrainConcurrencyTest {
    @Test
    void interruptedTurnPreservesTheSignalAndDoesNotCallTheModel() throws Exception {
        var ai = mock(BotAiClient.class);
        var notify = mock(RealtimeNotifyClient.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SessionService> provider = mock(ObjectProvider.class);
        var brain = new BotBrainService(ai, notify, provider, true, true, 5);
        var preserved = new java.util.concurrent.atomic.AtomicBoolean();
        var worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            brain.handleCustomerMessage("TENANT_A", "S1");
            preserved.set(Thread.currentThread().isInterrupted());
        });
        try {
            worker.start();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertTrue(preserved.get());
            verifyNoInteractions(ai, provider, notify);
        } finally {
            brain.shutdown();
        }
    }

    @Test
    void aFinishingMustNotLetCOverlapWithAlreadyWaitingB() throws Exception {
        var ai = mock(BotAiClient.class);
        var notify = mock(RealtimeNotifyClient.class);
        var sessions = mock(SessionService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SessionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sessions);
        when(sessions.requireSession("TENANT_A", "S1")).thenReturn(
                Session.builder().id(1L).sessionNo("S1").status(SessionService.STATUS_BOT).build());
        when(sessions.historyByTenant(eq("TENANT_A"), eq("S1"), isNull(), anyInt(), eq(false)))
                .thenReturn(List.of());
        var enteredA = new CountDownLatch(1);
        var enteredB = new CountDownLatch(1);
        var enteredC = new CountDownLatch(1);
        var releaseA = new CountDownLatch(1);
        var releaseOthers = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        var failures = new ConcurrentLinkedQueue<Throwable>();
        when(ai.think(eq("TENANT_A"), eq("S1"), anyList(), eq(5))).thenAnswer(call -> {
            assertEquals("TENANT_A", TenantContext.get());
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                switch (Thread.currentThread().getName()) {
                    case "turn-A" -> { enteredA.countDown(); await(releaseA); }
                    case "turn-B" -> { enteredB.countDown(); await(releaseOthers); }
                    case "turn-C" -> { enteredC.countDown(); await(releaseOthers); }
                    default -> fail("Unexpected worker");
                }
                return Map.of("reply", "", "bot_enabled", true);
            } finally {
                active.decrementAndGet();
            }
        });
        var brain = new BotBrainService(ai, notify, provider, true, true, 5);
        Runnable turn = () -> {
            try {
                brain.handleCustomerMessage("TENANT_A", "S1");
                assertNull(TenantContext.get(), "Tenant context must exit with the turn");
            } catch (Throwable failure) {
                failures.add(failure);
            }
        };
        var a = new Thread(turn, "turn-A");
        var b = new Thread(turn, "turn-B");
        var c = new Thread(turn, "turn-C");
        try {
            a.start();
            await(enteredA);
            b.start();
            until(() -> parked(b), "B must already be waiting for A's lock");
            releaseA.countDown();
            await(enteredB);
            a.join(5000); // includes A's finally/map cleanup
            assertFalse(a.isAlive());
            c.start();
            until(() -> parked(c), "C must either wait on the lock or enter the blocked AI stub");
            assertEquals(1, maximum.get(), "C overlapped B after A removed the shared lock");
        } finally {
            releaseA.countDown();
            releaseOthers.countDown();
            for (var thread : List.of(a, b, c)) {
                thread.join(5000);
                assertFalse(thread.isAlive(), thread.getName());
            }
            brain.shutdown();
        }
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(0, enteredC.getCount());
        verify(ai, times(3)).think(eq("TENANT_A"), eq("S1"), anyList(), eq(5));
    }

    private static boolean parked(Thread thread) {
        return thread.getState() == Thread.State.BLOCKED || thread.getState() == Thread.State.WAITING
                || thread.getState() == Thread.State.TIMED_WAITING;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(8, TimeUnit.SECONDS), "Worker did not reach the expected checkpoint");
    }

    private static void until(BooleanSupplier condition, String reason) {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            while (!condition.getAsBoolean()) {
                if (Thread.currentThread().isInterrupted()) fail(reason);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
        }, reason);
    }
}
