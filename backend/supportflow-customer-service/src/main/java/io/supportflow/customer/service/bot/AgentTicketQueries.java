package io.supportflow.customer.service.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportflow.common.exception.BizException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

/** Minimal visitor-facing facts. Does not impersonate an enterprise member. */
public final class AgentTicketQueries {
    public record Status(String tenant, UUID eventId, UUID runId, UUID snapshotId, String sourceHash,
                         String ticketNo, int status, String updatedAt, String resourceVersion) { }
    private record TicketRow(String ticketNo, Integer status, LocalDateTime updatedAt) { }
    private final JdbcTemplate jdbc;
    private final AgentSourceSnapshots snapshots;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentTicketQueries(JdbcTemplate jdbc) {
        this.jdbc=jdbc; snapshots=new AgentSourceSnapshots(jdbc);
    }

    public Status read(String tenant, UUID eventId, UUID runId, String ticketNo) {
        if (ticketNo==null || !ticketNo.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            throw new BizException(42204,"工单查询参数无效");
        // Validates Scope/transaction, takes current source locks and refuses
        // takeover/closed/changed/expired input. Snapshot capture is idempotent.
        var source=snapshots.capture(tenant,eventId,runId);
        String customer;
        try {
            var field=mapper.readTree(source.payloadJson()).path("trusted_source").path("customer_ref");
            if(!field.isNull() && !field.isTextual()) throw new IllegalStateException("Invalid trusted customer binding");
            customer=field.isNull()?null:field.textValue();
        }
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("Invalid trusted source snapshot"); }
        Long customerId=null;
        if(customer!=null) {
            try {
                if(!customer.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
                customerId=Long.valueOf(customer);
            } catch(NumberFormatException invalid) { throw unavailable(); }
            if(jdbc.queryForList("SELECT id FROM customer WHERE tenant_code=? AND id=? AND is_deleted=false AND anonymized_at IS NULL AND merged_into IS NULL FOR SHARE",Long.class,tenant,customerId).size()!=1)
                throw unavailable();
        }
        var rows=jdbc.query("""
                SELECT t.ticket_no,t.status,coalesce(t.update_time,t.create_time) AS updated_at
                FROM ticket t JOIN agent_event_outbox e ON e.tenant_code=t.tenant_code
                JOIN session s ON s.id=e.session_id AND s.tenant_code=e.tenant_code AND s.session_no=e.session_no
                WHERE t.tenant_code=? AND e.event_id=? AND e.run_id=? AND t.ticket_no=?
                  AND t.ticket_type=1 AND t.is_deleted=false
                  AND ((?::bigint IS NOT NULL AND t.customer_id=?::bigint)
                    OR (?::bigint IS NULL AND t.customer_id IS NULL AND t.source_session_id=s.id AND t.session_no=s.session_no))
                FOR SHARE OF t
                """,(row,index)->new TicketRow(row.getString("ticket_no"),(Integer)row.getObject("status"),
                row.getTimestamp("updated_at")==null?null:row.getTimestamp("updated_at").toLocalDateTime()),
                tenant,eventId,runId,ticketNo,customerId,customerId,customerId);
        if(rows.size()!=1) throw unavailable();
        var ticket=rows.getFirst();
        if(ticket.status()==null || ticket.status()<1 || ticket.status()>5 || ticket.updatedAt()==null)
            throw new BizException(42204,"工单状态需要核验");
        String updated=DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(ticket.updatedAt());
        String canonical=String.join("\n","supportflow-ticket-status:v1",tenant,eventId.toString(),runId.toString(),
                source.snapshotId().toString(),source.payloadHash(),ticket.ticketNo(),Integer.toString(ticket.status()),updated);
        return new Status(tenant,eventId,runId,source.snapshotId(),source.payloadHash(),ticket.ticketNo(),ticket.status(),updated,digest(canonical));
    }

    private static BizException unavailable() { return new BizException(40404,"可访问的工单不存在"); }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.GeneralSecurityException impossible) { throw new IllegalStateException("Ticket digest unavailable"); }
    }
}
