package io.supportflow.customer.service.bot;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single-process per-tenant/session mutual exclusion. References include waiters,
 * so an entry cannot disappear while a later turn still uses its lock.
 * Distributed ordering and durable scheduling belong to the persistent runtime.
 */
final class SessionSerialExecutor {
    private record Key(String tenantCode, String sessionNo) {}

    private static final class Entry {
        private final ReentrantLock lock = new ReentrantLock(true);
        // Accessed only inside entries.compute for this key.
        private int references;
    }

    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();

    void execute(String tenantCode, String sessionNo, Runnable action) throws InterruptedException {
        var key = new Key(Objects.requireNonNull(tenantCode), Objects.requireNonNull(sessionNo));
        Objects.requireNonNull(action);
        var entry = entries.compute(key, (ignored, existing) -> {
            var registered = existing == null ? new Entry() : existing;
            registered.references++;
            return registered;
        });
        boolean acquired = false;
        try {
            entry.lock.lockInterruptibly();
            acquired = true;
            action.run();
        } finally {
            if (acquired) entry.lock.unlock();
            entries.compute(key, (ignored, existing) -> {
                if (existing != entry) throw new IllegalStateException("Session lock lifecycle violated");
                return --entry.references == 0 ? null : entry;
            });
        }
    }

    int activeKeys() {
        return entries.size();
    }
}
