package io.supportflow.customer.service.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.supportflow.common.context.TenantContext;
import io.supportflow.common.exception.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Immutable source storage, not yet an exposed API or a context compression engine. */
public final class AgentSourceSnapshots {
    public record Snapshot(String tenant, UUID eventId, UUID runId, UUID snapshotId, String schemaVersion,
                           String payloadJson, String payloadHash, Instant createdAt, Instant expiresAt) { }
    private record Source(String sessionNo, long messageId, long sequence, String principal,
                          String customerRef, String text, Integer textBytes) { }
    private record Stored(Snapshot snapshot, boolean unexpired) { }
    private static final RowMapper<Stored> ROW = (row, index) -> new Stored(new Snapshot(
            row.getString("tenant_code"), row.getObject("event_id", UUID.class), row.getObject("run_id", UUID.class),
            row.getObject("snapshot_id", UUID.class), row.getString("schema_version"), row.getString("payload_json"),
            row.getString("payload_hash"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("expires_at").toInstant()),
            row.getBoolean("unexpired"));
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public AgentSourceSnapshots(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Snapshot capture(String tenant, UUID eventId, UUID runId) {
        requireScope(tenant, eventId, runId);
        String payload = eligiblePayload(tenant, eventId, runId);
        String digest = digest(payload);
        Stored previous = stored(tenant, eventId, runId);
        if (previous != null) return validate(previous, digest);
        // No unique-violation catch inside a PG transaction. Concurrent callers
        // wait for the same key and then read the already-committed exact bytes.
        var created = jdbc.query("""
                INSERT INTO agent_source_snapshot(tenant_code,event_id,run_id,snapshot_id,schema_version,payload_json,payload_hash)
                VALUES(?,?,?,?,'source-v1',?,?) ON CONFLICT DO NOTHING
                RETURNING *,expires_at>clock_timestamp() AS unexpired
                """, ROW, tenant, eventId, runId, UUID.randomUUID(), payload, digest);
        Stored value = created.isEmpty() ? stored(tenant, eventId, runId) : created.getFirst();
        if (value == null) throw new BizException(40902, "来源快照冲突");
        return validate(value, digest);
    }

    public Snapshot read(String tenant, UUID eventId, UUID runId) {
        requireScope(tenant, eventId, runId);
        String payload = eligiblePayload(tenant, eventId, runId); // authorization is never cached
        Stored value = stored(tenant, eventId, runId);
        if (value == null) throw unavailable();
        return validate(value, digest(payload));
    }

    private String eligiblePayload(String tenant, UUID eventId, UUID runId) {
        var rows = jdbc.query("""
                SELECT e.session_no,e.message_id,e.event_seq,e.principal_ref,s.customer_id,
                       left(m.content,8192) AS content,octet_length(m.content) AS content_bytes
                FROM agent_event_outbox e
                JOIN session s ON s.id=e.session_id AND s.tenant_code=e.tenant_code AND s.session_no=e.session_no
                JOIN session_message m ON m.id=e.message_id AND m.tenant_code=e.tenant_code
                  AND m.session_id=e.session_id AND m.seq=e.event_seq
                WHERE e.tenant_code=? AND e.event_id=? AND e.run_id=? AND e.state='DELIVERED'
                  AND e.runtime_version='runtime-v1' AND e.principal_ref='visitor-session:'||s.id
                  AND s.is_deleted=false AND s.agent_id IS NULL AND s.status IN (1,2)
                  AND m.is_deleted=false AND m.visible_to=1 AND m.sender_type=1 AND m.msg_type=1
                FOR SHARE OF e,s,m
                """, (row, index) -> new Source(row.getString("session_no"), row.getLong("message_id"), row.getLong("event_seq"),
                row.getString("principal_ref"), row.getString("customer_id"), row.getString("content"),
                (Integer) row.getObject("content_bytes")), tenant, eventId, runId);
        if (rows.size() != 1) throw unavailable();
        Source source = rows.getFirst();
        if (source.text() == null || source.text().isBlank()) throw new BizException(42202, "来源需要有效文本");
        if (source.textBytes() == null || source.textBytes() > 8192) throw new BizException(41302, "来源文本超出快照上限");
        Map<String, Object> trusted = new TreeMap<>();
        trusted.put("tenant_code", tenant); trusted.put("source_event_id", eventId.toString()); trusted.put("run_id", runId.toString());
        trusted.put("session_no", source.sessionNo()); trusted.put("message_id", Long.toString(source.messageId()));
        trusted.put("event_seq", source.sequence()); trusted.put("principal_ref", source.principal()); trusted.put("customer_ref", source.customerRef());
        try {
            String payload = mapper.writeValueAsString(Map.of("schema_version", "source-v1", "trusted_source", trusted,
                    "untrusted_input", Map.of("role", "user", "content", source.text())));
            if (payload.getBytes(StandardCharsets.UTF_8).length > 65536) throw new BizException(41302, "来源快照超出上限");
            return payload;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Could not serialize source snapshot", failure);
        }
    }

    private Stored stored(String tenant, UUID eventId, UUID runId) {
        return jdbc.query("SELECT *,expires_at>clock_timestamp() AS unexpired FROM agent_source_snapshot WHERE tenant_code=? AND event_id=? AND run_id=?",
                ROW, tenant, eventId, runId).stream().findFirst().orElse(null);
    }

    private static Snapshot validate(Stored value, String digest) {
        if (!value.unexpired()) throw new BizException(41002, "来源快照已过期");
        if (!value.snapshot().payloadHash().equals(digest) || !value.snapshot().payloadHash().equals(digest(value.snapshot().payloadJson())))
            throw new BizException(40902, "来源已变更，需要重新核验任务");
        return value.snapshot();
    }

    private void requireScope(String tenant, UUID eventId, UUID runId) {
        if (tenant == null || !tenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}") || tenant.equalsIgnoreCase("PLATFORM")
                || !tenant.equals(TenantContext.get()) || !TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Bind the trusted tenant before the source snapshot transaction");
        if (eventId == null || runId == null) throw new IllegalArgumentException("Source event and run receipt are required");
        jdbc.execute("SET LOCAL lock_timeout='2s'; SET LOCAL statement_timeout='5s'");
    }
    private static BizException unavailable() { return new BizException(40402, "可用的任务来源不存在"); }
    private static String digest(String payload) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
}
