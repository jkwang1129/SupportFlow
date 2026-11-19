package io.supportflow.customer.service.bot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionSerialExecutorTest {
    @Test
    void exceptionReleasesTheKeyAndTheNextTurnCanRun() throws Exception {
        var serial = new SessionSerialExecutor();
        assertThrows(IllegalStateException.class, () -> serial.execute("A", "S", () -> {
            throw new IllegalStateException("synthetic failure");
        }));
        var ran = new AtomicBoolean();
        serial.execute("A", "S", () -> ran.set(true));
        assertTrue(ran.get());
        assertEquals(0, serial.activeKeys());
    }

    @Test
    void interruptedWaiterDoesNotExecuteOrRemoveTheOwnersLock() throws Exception {
        var serial = new SessionSerialExecutor();
        var ownerEntered = new CountDownLatch(1);
        var releaseOwner = new CountDownLatch(1);
        var waiterStarted = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var waiterRan = new AtomicBoolean();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var owner = pool.submit(() -> {
                serial.execute("A", "S", () -> { ownerEntered.countDown(); await(releaseOwner); });
                return null;
            });
            assertTrue(ownerEntered.await(5, TimeUnit.SECONDS));
            var waiter = new Thread(() -> {
                waiterStarted.countDown();
                try {
                    serial.execute("A", "S", () -> waiterRan.set(true));
                } catch (InterruptedException expected) {
                    interrupted.set(true);
                }
            });
            try {
                waiter.start();
                assertTrue(waiterStarted.await(5, TimeUnit.SECONDS));
                waiter.interrupt(); // also covers interruption before lock acquisition
                waiter.join(5000);
                assertFalse(waiter.isAlive());
                assertTrue(interrupted.get());
                assertFalse(waiterRan.get());
                assertEquals(1, serial.activeKeys(), "Owner still holds a live entry");
            } finally {
                releaseOwner.countDown();
                waiter.interrupt();
                waiter.join(5000);
            }
            owner.get(5, TimeUnit.SECONDS);
        }
        assertEquals(0, serial.activeKeys());
    }

    @Test
    void differentSessionsAndTenantsRunConcurrentlyIncludingDelimiterLikeKeys() throws Exception {
        var serial = new SessionSerialExecutor();
        // The first two keys would collide under tenant + '#' + session concatenation.
        var keys = List.of(List.of("A#B", "C"), List.of("A", "B#C"), List.of("A", "OTHER"));
        var entered = new CountDownLatch(keys.size());
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(keys.size())) {
            var futures = keys.stream().map(key -> pool.submit(() -> {
                serial.execute(key.get(0), key.get(1), () -> { entered.countDown(); await(release); });
                return null;
            })).toList();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS), "Unrelated keys must not block each other");
                assertEquals(3, serial.activeKeys());
            } finally {
                release.countDown();
            }
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        assertEquals(0, serial.activeKeys());
    }

    @Test
    void concurrentTurnsRemainExclusiveAcrossRepeatedKeyCreationAndCleanup() throws Exception {
        var serial = new SessionSerialExecutor();
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        var count = new AtomicInteger();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                futures.add(pool.submit(() -> {
                    await(start);
                    for (int turn = 0; turn < 100; turn++) {
                        serial.execute("A", "S", () -> {
                            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                            Thread.yield();
                            count.incrementAndGet();
                            active.decrementAndGet();
                        });
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertEquals(800, count.get());
        assertEquals(1, maximum.get());
        assertEquals(0, serial.activeKeys());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test worker");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
