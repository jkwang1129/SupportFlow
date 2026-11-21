package io.supportflow.customer.service.bot;

import io.supportflow.common.context.TenantContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Supplier;

/** One-slot delivery of persisted events, with both transactions outside the HTTP call. */
public final class AgentEventDispatcher {
    public enum Result { IDLE, BUSY, DELIVERED, RETRY, HELD, STALE, INTERRUPTED }
    private final AgentEventOutbox outbox;
    private final AgentRuntimeClient client;
    private final TransactionTemplate transaction;
    private final String owner = "dispatcher-" + UUID.randomUUID();
    private final java.util.concurrent.locks.ReentrantLock slot = new java.util.concurrent.locks.ReentrantLock();

    public AgentEventDispatcher(AgentEventOutbox outbox, PlatformTransactionManager manager, AgentRuntimeClient client) {
        this.outbox = outbox; this.client = client;
        this.transaction = new TransactionTemplate(manager);
        transaction.setTimeout(10);
    }

    public Result dispatchOne(String tenant) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Dispatch must run outside the business transaction");
        if (tenant == null || !tenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}") || tenant.equalsIgnoreCase("PLATFORM")
                || TenantContext.get() != null && !tenant.equals(TenantContext.get()))
            throw new IllegalArgumentException("Expected a trusted dispatcher tenant");
        if (Thread.currentThread().isInterrupted()) return Result.INTERRUPTED;
        if (!slot.tryLock()) return Result.BUSY;
        try { return deliver(tenant); }
        finally { slot.unlock(); }
    }

    private Result deliver(String tenant) {
        var lease = tx(tenant, () -> outbox.claim(tenant, owner, 30));
        if (lease == null) return Result.IDLE;
        if (Thread.currentThread().isInterrupted()) return Result.INTERRUPTED;
        try {
            var receipt = client.enqueue(lease);
            if (Thread.currentThread().isInterrupted()) return Result.INTERRUPTED;
            tx(tenant, () -> { outbox.acknowledge(lease, receipt.eventId(), receipt.runId()); return null; });
            return Result.DELIVERED;
        } catch (AgentRuntimeClient.DeliveryFailure failure) {
            if (Thread.currentThread().isInterrupted()) return Result.INTERRUPTED; // leave durable lease for takeover
            try {
                tx(tenant, () -> {
                    if (failure.retryable()) outbox.retry(lease, failure.code(), Math.min(60, 1 << Math.min(6, lease.attempts() - 1)));
                    else outbox.hold(lease, failure.code());
                    return null;
                });
            } catch (AgentEventOutbox.DeliveryLeaseLost stale) { return Result.STALE; }
            return failure.retryable() && lease.attempts() < 8 ? Result.RETRY : Result.HELD;
        } catch (AgentEventOutbox.DeliveryLeaseLost stale) { return Result.STALE; }
    }

    private <T> T tx(String tenant, Supplier<T> action) {
        return TenantContext.bind(tenant, () -> transaction.execute(status -> action.get()));
    }
}
