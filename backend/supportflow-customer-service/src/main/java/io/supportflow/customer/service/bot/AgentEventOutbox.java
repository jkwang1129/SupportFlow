package io.supportflow.customer.service.bot;

import io.supportflow.common.context.TenantContext;
import io.supportflow.common.context.TraceContext;
import io.supportflow.common.exception.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Durable source-event primitives. Caller binds tenant BEFORE a short transaction.
 * Not automatically wired into SessionService; explicit delivery commits before network I/O.
 */
@Component
public class AgentEventOutbox {
    public static final class DeliveryLeaseLost extends IllegalStateException {
        DeliveryLeaseLost() { super("Event delivery lease was lost"); }
    }
    public record Event(String tenant, UUID eventId, long messageId, String sessionNo, long sequence,
                        String principalRef, String traceId, String runtimeVersion, String state,
                        int attempts, long fence, String owner, Instant leaseUntil, UUID runId) {}

    private static final RowMapper<Event> ROW = (row, index) -> {
        Timestamp lease = row.getTimestamp("lease_until");
        return new Event(row.getString("tenant_code"), row.getObject("event_id", UUID.class), row.getLong("message_id"),
                row.getString("session_no"), row.getLong("event_seq"), row.getString("principal_ref"), row.getString("trace_id"),
                row.getString("runtime_version"), row.getString("state"), row.getInt("attempts"), row.getLong("fence"),
                row.getString("owner"), lease == null ? null : lease.toInstant(), row.getObject("run_id", UUID.class));
    };
    private final JdbcTemplate jdbc;

    public AgentEventOutbox(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Event enqueue(String tenant, long messageId, String runtimeVersion, int backlogLimit) {
        requireScope(tenant);
        requireId(runtimeVersion, 32);
        if (messageId <= 0 || backlogLimit < 1 || backlogLimit > 10000) throw new IllegalArgumentException("Invalid event admission");
        // Serializes only tenant admission, never network delivery or model work.
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?,0))", Integer.class, "agent-admit:" + tenant);
        Event previous = getByMessage(tenant, messageId);
        if (previous != null) {
            if (!previous.runtimeVersion().equals(runtimeVersion)) throw new BizException(40901, "事件版本冲突");
            return previous;
        }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM agent_event_outbox WHERE tenant_code=? AND state<>'DELIVERED'", Integer.class, tenant);
        if (count != null && count >= backlogLimit) throw new BizException(42901, "智能任务队列已满，请稍后重试");
        String trace = TraceContext.getOrCreate();
        if (!validId(trace, 64)) trace = TraceContext.newTraceId();
        var rows = jdbc.query("""
                INSERT INTO agent_event_outbox(tenant_code,event_id,message_id,session_id,session_no,event_seq,principal_ref,trace_id,runtime_version)
                SELECT m.tenant_code,CAST(? AS uuid),m.id,s.id,s.session_no,m.seq,'visitor-session:'||s.id,?,?
                FROM session_message m JOIN session s ON s.id=m.session_id AND s.tenant_code=m.tenant_code
                WHERE m.tenant_code=? AND m.id=? AND m.sender_type=1 AND m.visible_to=1
                  AND m.is_deleted=false AND s.is_deleted=false
                RETURNING *
                """, ROW, UUID.randomUUID().toString(), trace, runtimeVersion, tenant, messageId);
        if (rows.size() != 1) throw new BizException(40401, "可用的访客消息不存在");
        return rows.getFirst();
    }

    public Event getByMessage(String tenant, long messageId) {
        requireScope(tenant);
        return jdbc.query("SELECT * FROM agent_event_outbox WHERE tenant_code=? AND message_id=?", ROW, tenant, messageId)
                .stream().findFirst().orElse(null);
    }

    public Event claim(String tenant, String owner, int leaseSeconds) {
        requireScope(tenant); requireId(owner, 64);
        if (leaseSeconds < 1 || leaseSeconds > 60) throw new IllegalArgumentException("Invalid event lease");
        // A lost response is safely retransmitted with the same persisted event ID.
        // Exhaustion is retained/held, never silently discarded or auto-acknowledged.
        jdbc.update("""
                UPDATE agent_event_outbox SET state='HELD',owner=NULL,lease_until=NULL,fence=fence+1,error_code='DELIVERY_EXHAUSTED'
                WHERE tenant_code=? AND attempts>=8 AND (state='PENDING' OR (state='SENDING' AND lease_until<=clock_timestamp()))
                """, tenant);
        return jdbc.query("""
                WITH candidate AS (
                 SELECT e.event_id FROM agent_event_outbox e WHERE e.tenant_code=? AND e.attempts<8 AND
                   ((e.state='PENDING' AND e.ready_at<=clock_timestamp()) OR (e.state='SENDING' AND e.lease_until<=clock_timestamp()))
                   AND NOT EXISTS(SELECT 1 FROM agent_event_outbox earlier WHERE earlier.tenant_code=e.tenant_code
                     AND earlier.session_no=e.session_no AND earlier.event_seq<e.event_seq AND earlier.state<>'DELIVERED')
                 ORDER BY e.created_at,e.event_seq,e.event_id FOR UPDATE SKIP LOCKED LIMIT 1
                ) UPDATE agent_event_outbox e SET state='SENDING',owner=?,fence=fence+1,attempts=attempts+1,
                    lease_until=clock_timestamp()+?*interval '1 second'
                 FROM candidate c WHERE e.tenant_code=? AND e.event_id=c.event_id RETURNING e.*
                """, ROW, tenant, owner, leaseSeconds, tenant).stream().findFirst().orElse(null);
    }

    public void acknowledge(Event lease, UUID acceptedEventId, UUID runId) {
        requireScope(lease.tenant());
        if (!lease.eventId().equals(acceptedEventId) || runId == null) throw new IllegalArgumentException("AI receipt does not match source event");
        owned(lease);
        jdbc.update("""
                UPDATE agent_event_outbox SET state='DELIVERED',run_id=CAST(? AS uuid),delivered_at=clock_timestamp(),
                  owner=NULL,lease_until=NULL,fence=fence+1,error_code=NULL WHERE tenant_code=? AND event_id=CAST(? AS uuid)
                """, runId.toString(), lease.tenant(), lease.eventId().toString());
    }

    public void retry(Event lease, String errorCode, int delaySeconds) {
        requireScope(lease.tenant()); requireId(errorCode, 64);
        if (delaySeconds < 0 || delaySeconds > 300) throw new IllegalArgumentException("Invalid retry delay");
        owned(lease);
        jdbc.update("""
                UPDATE agent_event_outbox SET state=CASE WHEN attempts>=8 THEN 'HELD' ELSE 'PENDING' END,
                  owner=NULL,lease_until=NULL,fence=fence+1,error_code=?,ready_at=clock_timestamp()+?*interval '1 second'
                  WHERE tenant_code=? AND event_id=CAST(? AS uuid)
                """, errorCode, delaySeconds, lease.tenant(), lease.eventId().toString());
    }

    public void hold(Event lease, String errorCode) {
        requireScope(lease.tenant()); requireId(errorCode, 64);
        owned(lease);
        jdbc.update("""
                UPDATE agent_event_outbox SET state='HELD',owner=NULL,lease_until=NULL,fence=fence+1,error_code=?
                WHERE tenant_code=? AND event_id=CAST(? AS uuid)
                """, errorCode, lease.tenant(), lease.eventId().toString());
    }

    private void owned(Event lease) {
        // Recheck the database clock after locking, so waiting cannot revive a lease.
        var rows = jdbc.query("""
                SELECT * FROM agent_event_outbox WHERE tenant_code=? AND event_id=CAST(? AS uuid)
                  AND state='SENDING' AND owner=? AND fence=? FOR UPDATE
                """, ROW, lease.tenant(), lease.eventId().toString(), lease.owner(), lease.fence());
        if (rows.size() != 1 || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT ?::timestamptz>clock_timestamp()", Boolean.class,
                Timestamp.from(rows.getFirst().leaseUntil())))) throw new DeliveryLeaseLost();
    }

    private void requireScope(String tenant) {
        if (tenant == null || !tenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}") || tenant.equalsIgnoreCase("PLATFORM")
                || !tenant.equals(TenantContext.get()) || !TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Bind the trusted tenant before opening the event transaction");
        jdbc.execute("SET LOCAL lock_timeout='2s'; SET LOCAL statement_timeout='5s'");
    }
    private static boolean validId(String value, int max) {
        return value != null && value.length() <= max && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*");
    }
    private static void requireId(String value, int max) {
        if (!validId(value, max)) throw new IllegalArgumentException("Invalid event reference");
    }
}
