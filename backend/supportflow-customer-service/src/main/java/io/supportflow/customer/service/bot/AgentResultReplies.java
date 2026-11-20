package io.supportflow.customer.service.bot;

import io.supportflow.common.context.TenantContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.service.session.SessionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.UUID;

/** Explicit transaction-scoped writer. No scheduler or external callback is enabled here. */
public final class AgentResultReplies {
    public record Receipt(UUID runId, UUID eventId, UUID snapshotId, String argumentsSha256,
                          long messageId, String messageNo, long sequence) { }
    private record Target(long id, String no) { }
    private final JdbcTemplate jdbc;
    private final AgentSourceSnapshots snapshots;
    private final SessionService sessions;

    public AgentResultReplies(JdbcTemplate jdbc, AgentSourceSnapshots snapshots, SessionService sessions) {
        this.jdbc = jdbc; this.snapshots = snapshots; this.sessions = sessions;
    }

    /** Safe short-transaction preflight for retries; returns references only, never executes a write. */
    public Receipt find(AgentReplyArguments reply) {
        requireScope(reply);
        var rows = jdbc.query("SELECT * FROM agent_result_receipt WHERE tenant_code=? AND run_id=?", (row, index) ->
                new Receipt(row.getObject("run_id", UUID.class), row.getObject("event_id", UUID.class), row.getObject("snapshot_id", UUID.class),
                        row.getString("arguments_sha256"), row.getLong("message_id"), row.getString("message_no"), row.getLong("message_seq")),
                reply.tenant(), reply.runId());
        if (rows.isEmpty()) return null;
        var result = rows.getFirst();
        if (!result.eventId().equals(reply.eventId()) || !result.snapshotId().equals(reply.snapshotId())
                || !result.argumentsSha256().equals(reply.argumentsSha256())) throw conflict();
        return result;
    }

    public Receipt append(AgentActionPermit permit, AgentReplyArguments reply) {
        requireScope(reply);
        if (permit == null || !permit.request().tenant().equals(reply.tenant()) || !permit.request().runId().equals(reply.runId())
                || !permit.request().eventId().equals(reply.eventId()) || !permit.request().snapshotId().equals(reply.snapshotId())
                || !permit.request().argumentsSha256().equals(reply.argumentsSha256())) throw conflict();
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, lockKey(reply));
        var existing = find(reply);
        if (existing != null) return existing;
        // Lock UPDATE before SourceSnapshots' SHARE locks; another writer must
        // never hold SHARE on this session while both try to upgrade it.
        var session = jdbc.query("""
                SELECT s.id,s.session_no FROM session s JOIN agent_event_outbox e ON e.tenant_code=s.tenant_code AND e.session_id=s.id
                WHERE e.tenant_code=? AND e.event_id=? AND e.run_id=? AND e.state='DELIVERED'
                  AND s.is_deleted=false AND s.status IN (1,2) AND s.agent_id IS NULL
                FOR UPDATE OF s
                """, (row, index) -> new Target(row.getLong("id"), row.getString("session_no")), reply.tenant(), reply.eventId(), reply.runId());
        if (session.size()!=1) throw new BizException(40403, "可回复的任务来源不存在");
        var snapshot = snapshots.read(reply.tenant(), reply.eventId(), reply.runId());
        if (!snapshot.snapshotId().equals(reply.snapshotId())) throw conflict();
        validNow(permit);
        var saved = sessions.appendMessage(reply.tenant(), session.getFirst().no(), SessionService.SENDER_BOT, null,
                1, reply.content(), SessionService.VISIBLE_ALL, "agent-run:" + reply.runId());
        // Client message keys may have been supplied by another caller. Never
        // acknowledge an existing row with a different role, target or content.
        if (!reply.content().equals(saved.content()) || !Integer.valueOf(SessionService.SENDER_BOT).equals(saved.senderType())
                || saved.senderId()!=null || !Integer.valueOf(1).equals(saved.msgType()) || !Integer.valueOf(SessionService.VISIBLE_ALL).equals(saved.visibleTo())
                || !Long.valueOf(session.getFirst().id()).equals(saved.sessionId()) || saved.seq()==null || saved.seq()<=0) throw conflict();
        jdbc.update("""
                INSERT INTO agent_result_receipt(tenant_code,run_id,event_id,snapshot_id,arguments_sha256,operation,schema_version,
                   step_key,step_no,fence,message_id,message_no,message_seq)
                VALUES(?,?,?,?,?,'session.reply','reply-v1',?,?,?,?,?,?)
                """, reply.tenant(), reply.runId(), reply.eventId(), reply.snapshotId(), reply.argumentsSha256(),
                permit.stepKey(), permit.stepNo(), permit.request().fence(), Long.parseLong(saved.msgId()), saved.msgNo(), saved.seq());
        // If another listener or DB operation consumed the short window, the
        // message, receipt, sequence and afterCommit effects must all roll back.
        validNow(permit);
        return new Receipt(reply.runId(), reply.eventId(), reply.snapshotId(), reply.argumentsSha256(),
                Long.parseLong(saved.msgId()), saved.msgNo(), saved.seq());
    }

    private void validNow(AgentActionPermit permit) {
        var now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        try { permit.requireValidAt(now.toInstant()); }
        catch (IllegalStateException expired) { throw new BizException(40903, "执行许可已过期"); }
    }
    private void requireScope(AgentReplyArguments reply) {
        if (reply == null || !reply.tenant().equals(TenantContext.get()) || !TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Bind the trusted tenant before the result transaction");
        jdbc.execute("SET LOCAL lock_timeout='2s'; SET LOCAL statement_timeout='5s'");
    }
    private static long lockKey(AgentReplyArguments reply) {
        try { return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(("agent-reply\n" + reply.tenant() + "\n" + reply.runId()).getBytes(StandardCharsets.UTF_8))).getLong(); }
        catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static BizException conflict() { return new BizException(40903, "任务结果与已登记内容不一致"); }
}
