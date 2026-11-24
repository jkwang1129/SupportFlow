package io.supportflow.customer.service.bot;

import io.supportflow.common.context.TenantContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.dto.ticket.ChannelTicketCommand;
import io.supportflow.customer.event.AgentTicketNotice;
import io.supportflow.customer.service.ticket.TicketService;
import io.supportflow.customer.service.session.SessionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;

/** Trusted-host business boundary; no endpoint, scheduler or model tool is registered here. */
public final class AgentTicketCreations {
    public record Receipt(UUID runId,UUID eventId,UUID snapshotId,String argumentsSha256,long ticketId,String ticketNo){}
    private record Source(long id,String no,Long customerId){}
    private final JdbcTemplate jdbc;
    private final AgentSourceSnapshots snapshots;
    private final TicketService tickets;
    private final SessionService sessions;
    private final ApplicationEventPublisher events;
    public AgentTicketCreations(JdbcTemplate jdbc,TicketService tickets){this(jdbc,tickets,null);}
    public AgentTicketCreations(JdbcTemplate jdbc,TicketService tickets,SessionService sessions){
        this(jdbc,tickets,sessions,null);
    }
    public AgentTicketCreations(JdbcTemplate jdbc,TicketService tickets,SessionService sessions,ApplicationEventPublisher events){
        this.jdbc=jdbc;this.tickets=tickets;this.sessions=sessions;this.events=events;snapshots=new AgentSourceSnapshots(jdbc);
    }

    /** Confirms only the committed creation, never current ticket status or another execution. */
    public Receipt get(String tenant,UUID event,UUID run){
        requireScope(tenant);
        if(event==null || run==null)throw new IllegalArgumentException("Ticket result references required");
        var rows=jdbc.query("SELECT * FROM agent_ticket_receipt WHERE tenant_code=? AND run_id=?",(row,index)->
            new Receipt(row.getObject("run_id",UUID.class),row.getObject("event_id",UUID.class),row.getObject("snapshot_id",UUID.class),
                row.getString("arguments_sha256"),row.getLong("ticket_id"),row.getString("ticket_no")),tenant,run);
        return rows.isEmpty() || !rows.getFirst().eventId().equals(event)?null:rows.getFirst();
    }
    public Receipt find(AgentTicketArguments args){
        if(args==null)throw new IllegalArgumentException("Ticket arguments required");
        var receipt=get(args.tenant(),args.eventId(),args.runId());
        if(receipt!=null && (!receipt.snapshotId().equals(args.snapshotId()) || !receipt.argumentsSha256().equals(args.argumentsSha256())))throw conflict();
        return receipt;
    }
    public Receipt create(AgentActionPermit permit,AgentTicketArguments args){
        if(args==null)throw new IllegalArgumentException("Ticket arguments required");
        requireScope(args.tenant());
        if(permit==null || permit.request().operation()!=AgentActionPermit.Operation.TICKET_CREATE
                || !permit.request().tenant().equals(args.tenant()) || !permit.request().runId().equals(args.runId())
                || !permit.request().eventId().equals(args.eventId()) || !permit.request().snapshotId().equals(args.snapshotId())
                || !permit.request().argumentsSha256().equals(args.argumentsSha256()))throw conflict();
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,lockKey(args));
        var existing=find(args);if(existing!=null)return existing;
        // Lock the session before the source snapshot's shared locks. A notice
        // will allocate an ordinary session sequence in the same transaction.
        var sources=jdbc.query("""
            SELECT s.id,s.session_no,s.customer_id FROM session s JOIN agent_event_outbox e ON e.tenant_code=s.tenant_code AND e.session_id=s.id
            WHERE e.tenant_code=? AND e.event_id=? AND e.run_id=? AND e.state='DELIVERED'
            FOR UPDATE OF s
            """,(row,index)->new Source(row.getLong("id"),row.getString("session_no"),row.getObject("customer_id",Long.class)),args.tenant(),args.eventId(),args.runId());
        if(sources.size()!=1)throw new BizException(40406,"可建单的任务来源不存在");
        var snapshot=snapshots.read(args.tenant(),args.eventId(),args.runId());
        if(!snapshot.snapshotId().equals(args.snapshotId()))throw conflict();
        var source=sources.getFirst();
        if(source.id()<1 || source.customerId()!=null && source.customerId()<1)throw new BizException(40406,"可建单的任务来源不存在");
        if(source.customerId()!=null){
            // UPDATE directly: taking SHARE then upgrading through ticket_count
            // would deadlock simultaneous runs associated with the same customer.
            var customers=jdbc.queryForList("SELECT id FROM customer WHERE tenant_code=? AND id=? AND is_deleted=false FOR UPDATE",Long.class,args.tenant(),source.customerId());
            if(customers.size()!=1)throw new BizException(40406,"可建单的关联客户不存在");
        }
        validNow(permit);
        var created=tickets.createForAgent(args.tenant(),"AI 助手",new ChannelTicketCommand(args.title(),args.content(),AgentTicketArguments.CATEGORY,
            AgentTicketArguments.PRIORITY,source.customerId(),null,AgentTicketArguments.SOURCE_CHANNEL,source.no(),source.id(),null,null),args.runId());
        var rows=jdbc.query("SELECT * FROM ticket WHERE tenant_code=? AND ticket_no=? FOR SHARE",(row,index)->{
            if(!args.title().equals(row.getString("title")) || !args.content().equals(row.getString("desc"))
                    || !args.creatorRef().equals(row.getString("creator")) || !args.creatorRef().equals(row.getString("editor"))
                    || !source.no().equals(row.getString("session_no")) || !Objects.equals(source.id(),row.getObject("source_session_id",Long.class))
                    || !Objects.equals(source.customerId(),row.getObject("customer_id",Long.class)) || row.getInt("ticket_type")!=1
                    || row.getInt("category")!=AgentTicketArguments.CATEGORY || row.getInt("priority")!=AgentTicketArguments.PRIORITY
                    || row.getInt("source_channel")!=AgentTicketArguments.SOURCE_CHANNEL || row.getInt("status")!=1
                    || row.getObject("assignee_id")!=null || row.getBoolean("is_deleted"))throw new BizException(42206,"建单结果需要核验");
            return row.getLong("id");
        },args.tenant(),created.ticketNo());
        if(rows.size()!=1 || rows.getFirst()<1)throw new BizException(42206,"建单结果需要核验");
        long ticketId=rows.getFirst();
        jdbc.update("""
            INSERT INTO agent_ticket_receipt(tenant_code,run_id,event_id,snapshot_id,arguments_sha256,operation,schema_version,
                step_key,step_no,fence,ticket_id,ticket_no) VALUES(?,?,?,?,?,'ticket.create','ticket-create-v1',?,?,?,?,?)
            """,args.tenant(),args.runId(),args.eventId(),args.snapshotId(),args.argumentsSha256(),permit.stepKey(),permit.stepNo(),permit.request().fence(),ticketId,created.ticketNo());
        if (permit.requiresApproval()) appendNotice(args,source,created.ticketNo());
        var after=snapshots.read(args.tenant(),args.eventId(),args.runId());
        if(!after.snapshotId().equals(snapshot.snapshotId()) || !after.payloadHash().equals(snapshot.payloadHash()))throw conflict();
        validNow(permit);
        return new Receipt(args.runId(),args.eventId(),args.snapshotId(),args.argumentsSha256(),ticketId,created.ticketNo());
    }
    private void appendNotice(AgentTicketArguments args,Source source,String ticketNo){
        if (sessions==null || ticketNo==null || !ticketNo.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw conflict();
        String text="工单已创建，编号："+ticketNo;
        var saved=sessions.appendMessage(args.tenant(),source.no(),SessionService.SENDER_SYSTEM,null,
                5,text,SessionService.VISIBLE_ALL,"agent-ticket-notice:"+args.runId());
        if (!text.equals(saved.content()) || !Integer.valueOf(SessionService.SENDER_SYSTEM).equals(saved.senderType())
                || saved.senderId()!=null || !Integer.valueOf(5).equals(saved.msgType())
                || !Integer.valueOf(SessionService.VISIBLE_ALL).equals(saved.visibleTo())
                || !Long.valueOf(source.id()).equals(saved.sessionId()) || saved.seq()==null || saved.seq()<=0) throw conflict();
        if (events!=null) events.publishEvent(new AgentTicketNotice(args.tenant(),source.no(),saved.seq(),saved.msgId()));
    }
    private void validNow(AgentActionPermit permit){
        var now=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class);
        try{permit.requireValidAt(now.toInstant());}catch(IllegalStateException expired){throw new BizException(40906,"建单执行许可已过期");}
    }
    private void requireScope(String tenant){
        if(tenant==null || !tenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}") || tenant.equalsIgnoreCase("PLATFORM")
                || !tenant.equals(TenantContext.get()) || !TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Bind the trusted tenant before the ticket transaction");
        jdbc.execute("SET LOCAL lock_timeout='2s'; SET LOCAL statement_timeout='5s'");
    }
    private static long lockKey(AgentTicketArguments args){
        try{return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(("agent-ticket-create\n"+args.tenant()+"\n"+args.runId()).getBytes(StandardCharsets.UTF_8))).getLong();}
        catch(java.security.GeneralSecurityException unavailable){throw new IllegalStateException("Ticket lock unavailable");}
    }
    private static BizException conflict(){return new BizException(40906,"建单参数或执行许可不一致");}
}
