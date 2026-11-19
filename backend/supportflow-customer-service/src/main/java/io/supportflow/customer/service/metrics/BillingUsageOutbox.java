package io.supportflow.customer.service.metrics;

import io.supportflow.common.billing.UsageBatch;
import io.supportflow.common.context.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Objects;
import java.util.function.Supplier;

/** One pending durable batch per tenant; the scheduler never performs an unscoped scan. */
@Component
public class BillingUsageOutbox {
    private final JdbcTemplate jdbc;
    public BillingUsageOutbox(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UsageBatch pending(String tenantCode) {
        return scoped(tenantCode, () -> jdbc.query("""
                SELECT batch_id::text, tenant_code, stat_date, sessions, messages, ai_calls, seats, storage_gb
                  FROM billing_usage_outbox WHERE tenant_code=?
                """, (row, index) -> new UsageBatch(row.getString(1), row.getString(2), row.getDate(3).toLocalDate(),
                row.getLong(4), row.getLong(5), row.getLong(6), row.getInt(7), row.getBigDecimal(8)), tenantCode)
                .stream().findFirst().orElse(null));
    }

    public boolean enqueue(UsageBatch batch) {
        return scoped(batch.tenantCode(), () -> {
            int added = jdbc.update("""
                    INSERT INTO billing_usage_outbox (batch_id,tenant_code,stat_date,sessions,messages,ai_calls,seats,storage_gb)
                    VALUES (CAST(? AS uuid),?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                    """, batch.batchId(), batch.tenantCode(), batch.statDate(), batch.sessions(), batch.messages(),
                    batch.aiCalls(), batch.seats(), batch.storageGb());
            if (added == 1) return true;
            var previous = pending(batch.tenantCode());
            if (previous != null && previous.batchId().equals(batch.batchId()) && !previous.equals(batch))
                throw new IllegalStateException("A pending usage batch cannot change contents");
            return batch.equals(previous);
        });
    }

    public void acknowledge(UsageBatch batch) {
        scoped(batch.tenantCode(), () -> jdbc.update(
                "DELETE FROM billing_usage_outbox WHERE tenant_code=? AND batch_id=CAST(? AS uuid)",
                batch.tenantCode(), batch.batchId()));
    }

    private static <T> T scoped(String tenant, Supplier<T> action) {
        String current = TenantContext.get();
        if (current != null && !current.isBlank() && !Objects.equals(current, tenant))
            throw new IllegalStateException("Cannot switch metering tenant inside an existing scope");
        return TenantContext.bind(tenant, action::get);
    }
}
