package io.supportflow.customer.service.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportflow.common.exception.BizException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Bounded visitor-visible session history projection.
 * Strictly excludes internal staff notes (visible_to != 1) and deleted records.
 */
public final class AgentSessionHistoryQueries {
    public static final int MAX_SESSIONS = 5;
    public static final int MAX_MESSAGES_PER_SESSION = 3;
    public static final int MAX_MESSAGE_CHARS = 240;

    public record MessageItem(long seq, int senderType, String content, String sentAt) { }

    public record SessionItem(
            String sessionNo,
            int status,
            String startedAt,
            String endedAt,
            List<MessageItem> messages) { }

    public record History(
            String tenant,
            UUID eventId,
            UUID runId,
            UUID snapshotId,
            String sourceHash,
            boolean bound,
            List<SessionItem> sessions,
            String resourceVersion) { }

    private record SessionRow(
            long id,
            String sessionNo,
            Integer status,
            LocalDateTime startedAt,
            LocalDateTime endedAt) { }

    private record MessageRow(
            Long seq,
            Integer senderType,
            String content,
            LocalDateTime sentAt) { }

    private final JdbcTemplate jdbc;
    private final AgentSourceSnapshots snapshots;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentSessionHistoryQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.snapshots = new AgentSourceSnapshots(jdbc);
    }

    public History read(String tenant, UUID eventId, UUID runId) {
        var source = snapshots.capture(tenant, eventId, runId);
        String customerRef = extractCustomerRef(source.payloadJson());
        boolean bound = customerRef != null;
        List<SessionRow> sessionRows;
        if (bound) {
            Long customerId = parseCustomerId(customerRef);
            if (jdbc.queryForList("""
                    SELECT id FROM customer
                    WHERE tenant_code = ? AND id = ? AND is_deleted = false
                      AND anonymized_at IS NULL AND merged_into IS NULL
                    FOR SHARE
                    """, Long.class, tenant, customerId).size() != 1) {
                throw unavailable();
            }
            sessionRows = jdbc.query("""
                    SELECT s.id, s.session_no, s.status,
                           coalesce(s.start_time, s.create_time) AS started_at,
                           s.end_time AS ended_at
                    FROM session s
                    WHERE s.tenant_code = ? AND s.customer_id = ? AND s.is_deleted = false
                    ORDER BY coalesce(s.start_time, s.create_time) DESC, s.id DESC
                    LIMIT 5
                    FOR SHARE OF s
                    """,
                    (row, index) -> new SessionRow(
                            row.getLong("id"),
                            row.getString("session_no"),
                            (Integer) row.getObject("status"),
                            toDateTime(row.getTimestamp("started_at")),
                            toDateTime(row.getTimestamp("ended_at"))),
                    tenant, customerId);
        } else {
            sessionRows = jdbc.query("""
                    SELECT s.id, s.session_no, s.status,
                           coalesce(s.start_time, s.create_time) AS started_at,
                           s.end_time AS ended_at
                    FROM agent_event_outbox e
                    JOIN session s ON s.id = e.session_id AND s.tenant_code = e.tenant_code AND s.session_no = e.session_no
                    WHERE e.tenant_code = ? AND e.event_id = ? AND e.run_id = ? AND s.is_deleted = false
                    ORDER BY s.id DESC
                    LIMIT 1
                    FOR SHARE OF s
                    """,
                    (row, index) -> new SessionRow(
                            row.getLong("id"),
                            row.getString("session_no"),
                            (Integer) row.getObject("status"),
                            toDateTime(row.getTimestamp("started_at")),
                            toDateTime(row.getTimestamp("ended_at"))),
                    tenant, eventId, runId);
            if (sessionRows.isEmpty()) {
                throw unavailable();
            }
        }

        List<SessionItem> sessions = new ArrayList<>(sessionRows.size());
        for (SessionRow sessionRow : sessionRows) {
            validateSessionRow(sessionRow);
            List<MessageItem> messages = readRecentVisibleMessages(tenant, sessionRow.id());
            String startedAt = formatTimestamp(sessionRow.startedAt());
            String endedAt = sessionRow.endedAt() == null ? null : formatTimestamp(sessionRow.endedAt());
            sessions.add(new SessionItem(
                    sessionRow.sessionNo(),
                    sessionRow.status(),
                    startedAt,
                    endedAt,
                    List.copyOf(messages)));
        }

        List<SessionItem> immutableSessions = List.copyOf(sessions);
        String canonical = canonicalDigestInput(
                tenant, eventId, runId, source.snapshotId(), source.payloadHash(), bound, immutableSessions);
        return new History(
                tenant, eventId, runId, source.snapshotId(), source.payloadHash(),
                bound, immutableSessions, digest(canonical));
    }

    private List<MessageItem> readRecentVisibleMessages(String tenant, long sessionId) {
        var rows = jdbc.query("""
                SELECT m.seq, m.sender_type, left(m.content, 240) AS content,
                       coalesce(m.send_time, m.create_time) AS sent_at
                FROM session_message m
                WHERE m.tenant_code = ? AND m.session_id = ?
                  AND m.is_deleted = false AND m.visible_to = 1 AND m.msg_type = 1
                  AND m.content IS NOT NULL AND btrim(m.content) <> ''
                ORDER BY m.seq DESC, m.id DESC
                LIMIT 3
                FOR SHARE OF m
                """,
                (row, index) -> new MessageRow(
                        (Long) row.getObject("seq"),
                        (Integer) row.getObject("sender_type"),
                        row.getString("content"),
                        toDateTime(row.getTimestamp("sent_at"))),
                tenant, sessionId);

        List<MessageItem> items = new ArrayList<>(rows.size());
        for (MessageRow row : rows) {
            if (row.seq() == null || row.seq() < 1
                    || row.senderType() == null || row.senderType() < 1 || row.senderType() > 4
                    || row.content() == null || row.sentAt() == null) {
                throw new BizException(42208, "会话历史记录状态需要核验");
            }
            String truncated = truncateCodePoints(row.content(), MAX_MESSAGE_CHARS);
            if (truncated.isBlank() || truncated.indexOf('\0') >= 0) {
                throw new BizException(42208, "会话历史记录状态需要核验");
            }
            items.add(new MessageItem(row.seq(), row.senderType(), truncated, formatTimestamp(row.sentAt())));
        }
        Collections.reverse(items);
        return items;
    }

    private String extractCustomerRef(String payloadJson) {
        try {
            var field = mapper.readTree(payloadJson).path("trusted_source").path("customer_ref");
            if (!field.isNull() && !field.isTextual()) {
                throw new IllegalStateException("Invalid trusted customer binding");
            }
            return field.isNull() ? null : field.textValue();
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid trusted source snapshot", invalid);
        }
    }

    private static Long parseCustomerId(String customerRef) {
        try {
            if (!customerRef.matches("[1-9][0-9]{0,18}")) {
                throw new NumberFormatException();
            }
            return Long.valueOf(customerRef);
        } catch (NumberFormatException invalid) {
            throw unavailable();
        }
    }

    private static void validateSessionRow(SessionRow row) {
        if (row.sessionNo() == null || !row.sessionNo().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")
                || row.status() == null || row.status() < 1 || row.status() > 4
                || row.startedAt() == null) {
            throw new BizException(42208, "会话历史记录状态需要核验");
        }
    }

    static String truncateCodePoints(String text, int maxCodePoints) {
        int length = text.codePointCount(0, text.length());
        if (length <= maxCodePoints) {
            return text;
        }
        int endIndex = text.offsetByCodePoints(0, maxCodePoints);
        return text.substring(0, endIndex);
    }

    private static LocalDateTime toDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime().truncatedTo(ChronoUnit.MICROS);
    }

    private static String formatTimestamp(LocalDateTime value) {
        return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value.truncatedTo(ChronoUnit.MICROS));
    }

    private static String canonicalDigestInput(
            String tenant,
            UUID eventId,
            UUID runId,
            UUID snapshotId,
            String sourceHash,
            boolean bound,
            List<SessionItem> sessions) {
        List<String> parts = new ArrayList<>();
        parts.add("supportflow-session-history:v1");
        parts.add(tenant);
        parts.add(eventId.toString());
        parts.add(runId.toString());
        parts.add(snapshotId.toString());
        parts.add(sourceHash);
        parts.add(Boolean.toString(bound));
        parts.add(Integer.toString(sessions.size()));
        for (SessionItem session : sessions) {
            parts.add(session.sessionNo());
            parts.add(Integer.toString(session.status()));
            parts.add(session.startedAt());
            parts.add(session.endedAt() == null ? "" : session.endedAt());
            parts.add(Integer.toString(session.messages().size()));
            for (MessageItem message : session.messages()) {
                parts.add(Long.toString(message.seq()));
                parts.add(Integer.toString(message.senderType()));
                parts.add(digest(message.content()));
                parts.add(message.sentAt());
            }
        }
        return String.join("\n", parts);
    }

    private static BizException unavailable() {
        return new BizException(40408, "可访问的会话历史不存在");
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("Session history digest unavailable", impossible);
        }
    }
}
