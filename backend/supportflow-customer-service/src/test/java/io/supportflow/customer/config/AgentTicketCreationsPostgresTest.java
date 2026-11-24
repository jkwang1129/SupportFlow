package io.supportflow.customer.config;

import io.supportflow.common.context.TenantContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.customer.dto.ticket.ChannelTicketCommand;
import io.supportflow.customer.internal.*;
import io.supportflow.customer.mapper.*;
import io.supportflow.customer.service.bot.*;
import io.supportflow.customer.service.customer.CustomerDynamics;
import io.supportflow.customer.service.notification.NotificationService;
import io.supportflow.customer.service.ticket.TicketService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual TicketService + all creation mappers; only optional external dependencies are synthetic. */
@EnabledIfEnvironmentVariable(named="SUPPORTFLOW_RLS_TEST_URL",matches=".+")
class AgentTicketCreationsPostgresTest extends SessionPostgresFixture {
    private TicketService tickets;
    private AgentTicketCreations creations;
    private AgentEventOutbox.Event event;
    private AgentTicketArguments args;
    private final UUID run=UUID.randomUUID();
    private long customerId,slaId;
    private RealtimeNotifyClient realtime;
    private NotificationService notifications;
    private UserRoleClient roles;
    private <T>T tx(Supplier<T> work){return scoped(()->transaction.execute(status->work.get()));}
    private void admin(String query){
        try(var db=DriverManager.getConnection(System.getenv("SUPPORTFLOW_RLS_TEST_URL"),"supportflow",System.getenv("SUPPORTFLOW_RLS_TEST_MIGRATION_PASSWORD"));var statement=db.createStatement()){statement.execute(query);}
        catch(Exception failure){throw new IllegalStateException("Disposable ticket administration failed",failure);}
    }
    @BeforeEach void prepareTicketService(){
        for(Class<?> mapper:List.of(TicketMapper.class,TicketEventMapper.class,TicketSlaRuleMapper.class,CustomerMapper.class,CustomerEventMapper.class))
            if(!sql.getConfiguration().hasMapper(mapper))sql.getConfiguration().addMapper(mapper);
        var generator=new SnowflakeIdGenerator(38);
        realtime=mock(RealtimeNotifyClient.class);notifications=mock(NotificationService.class);roles=mock(UserRoleClient.class);
        tickets=spy(new TicketService(sql.getMapper(TicketMapper.class),sql.getMapper(TicketEventMapper.class),sql.getMapper(TicketSlaRuleMapper.class),
            service,realtime,roles,notifications,sql.getMapper(CustomerMapper.class),new CustomerDynamics(sql.getMapper(CustomerEventMapper.class),generator),
            emptyProvider(),generator,emptyProvider(),emptyProvider(),mock(TenantListClient.class)));
        creations=new AgentTicketCreations(jdbc,tickets);
        slaId=IDS.incrementAndGet();
        tx(()->{jdbc.update("INSERT INTO ticket_sla_rule(id,tenant_code,priority,first_response_minutes,resolve_minutes,is_enabled,is_deleted) VALUES(?,'TENANT_A',2,7,21,true,false)",slaId);return null;});
    }
    private void source(boolean associatedCustomer){
        var outbox=new AgentEventOutbox(jdbc);
        var snapshot=tx(()->{
            if(associatedCustomer){
                customerId=IDS.incrementAndGet();
                jdbc.update("INSERT INTO customer(id,tenant_code,customer_no,name,ticket_count) VALUES(?,'TENANT_A',?,'PRIVATE CUSTOMER',3)",customerId,"C_AGENT_"+customerId);
                jdbc.update("UPDATE session SET customer_id=? WHERE id=?",customerId,sessionId);
            }
            var message=service.appendMessage("TENANT_A",sessionNo,1,null,1,"synthetic ticket source",1,"ticket-source");
            event=outbox.enqueue("TENANT_A",Long.parseLong(message.msgId()),"runtime-v1",500);
            outbox.acknowledge(outbox.claim("TENANT_A","ticket-business-test",30),event.eventId(),run);
            return new AgentSourceSnapshots(jdbc).capture("TENANT_A",event.eventId(),run);
        });
        args=new AgentTicketArguments("TENANT_A",run,event.eventId(),snapshot.snapshotId(),"synthetic issue","synthetic original body\n原文 ");
        clearInvocations(billing,brain);committedEvents.set(0);
    }
    private AgentActionPermit permit(){return AgentActionPermitFixtures.permit(args,Instant.now().plusSeconds(4));}
    private AgentTicketCreations.Receipt create(){var permit=permit();return tx(()->creations.create(permit,args));}
    private void facts(int ticketsExpected,int receiptsExpected){
        tx(()->{
            assertEquals(ticketsExpected,jdbc.queryForObject("SELECT count(*) FROM ticket WHERE source_session_id=?",Integer.class,sessionId));
            assertEquals(ticketsExpected,jdbc.queryForObject("SELECT count(*) FROM ticket_event WHERE ticket_id IN(SELECT id FROM ticket WHERE source_session_id=?)",Integer.class,sessionId));
            assertEquals(receiptsExpected,jdbc.queryForObject("SELECT count(*) FROM agent_ticket_receipt WHERE run_id=?",Integer.class,run));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM session_message WHERE session_id=?",Integer.class,sessionId));return null;
        });
        assertEquals(0,committedEvents.get());verifyNoInteractions(billing,brain,realtime,notifications,roles);
    }
    @Override @AfterEach void cleanup(){
        try{
            admin("DELETE FROM agent_ticket_receipt WHERE run_id='"+run+"'; DELETE FROM ticket_event WHERE ticket_id IN(SELECT id FROM ticket WHERE source_session_id="+sessionId+"); DELETE FROM ticket WHERE source_session_id="+sessionId);
            if(event!=null)admin("DELETE FROM agent_source_snapshot WHERE event_id='"+event.eventId()+"'; DELETE FROM agent_event_outbox WHERE event_id='"+event.eventId()+"'");
            if(customerId!=0)admin("DELETE FROM customer_event WHERE customer_id="+customerId);
        }finally{
            super.cleanup();
            if(customerId!=0)admin("DELETE FROM customer WHERE id="+customerId);
            if(slaId!=0)admin("DELETE FROM ticket_sla_rule WHERE id="+slaId);
        }
    }
    @Test void oneCommitReusesTicketNumberSlaTimelineAndCustomerDynamicsExactlyOnce(){
        source(true);var receipt=create();facts(1,1);
        assertEquals(receipt,tx(()->creations.find(args)));assertEquals(receipt,create());facts(1,1);
        tx(()->{
            var ticket=jdbc.queryForMap("SELECT * FROM ticket WHERE id=?",receipt.ticketId());
            assertEquals(args.title(),ticket.get("title"));assertEquals(args.content(),ticket.get("desc"));
            assertEquals(args.creatorRef(),ticket.get("creator"));assertEquals(args.creatorRef(),ticket.get("editor"));assertEquals("AI 助手",ticket.get("creator_name"));
            assertEquals(7,ticket.get("sla_first_minutes"));assertEquals(21,ticket.get("sla_resolve_minutes"));assertNull(ticket.get("assignee_id"));assertNull(ticket.get("customer_name"));
            assertEquals(4,jdbc.queryForObject("SELECT ticket_count FROM customer WHERE id=?",Integer.class,customerId));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM customer_event WHERE customer_id=? AND event_type=10 AND ref_no=?",Integer.class,customerId,receipt.ticketNo()));
            assertEquals("ticket-1",jdbc.queryForObject("SELECT step_key FROM agent_ticket_receipt WHERE run_id=?",String.class,run));return null;
        });
        verify(tickets,times(1)).createForAgent(anyString(),anyString(),any(ChannelTicketCommand.class),eq(run));
        assertFalse(receipt.toString().contains("PRIVATE"));
    }
    @Test void anonymousSourceHasNoInventedCustomerButStillUsesRealTicketLifecycle(){source(false);create();facts(1,1);}
    @Test void approvedNoticeIsVisibleOnceAndMatchesTheActualTicket(){
        source(true);
        var boundary=new AgentTicketCreations(jdbc,tickets,service);
        var permit=AgentActionPermitFixtures.noticePermit(args,Instant.now().plusSeconds(4));
        var receipt=tx(()->boundary.create(permit,args));
        assertEquals(receipt,tx(()->boundary.create(permit,args)));
        tx(()->{
            var history=service.messagesAfter("TENANT_A",sessionNo,1L,10,false);
            assertEquals(1,history.size());
            assertEquals("工单已创建，编号："+receipt.ticketNo(),history.getFirst().content());
            assertEquals(2L,history.getFirst().seq());
            assertEquals(4,history.getFirst().senderType());
            assertEquals(5,history.getFirst().msgType());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM agent_ticket_receipt WHERE run_id=?",Integer.class,run));
            return null;
        });
    }
    @Test void conflictingNoticeKeyRollsBackTicketReceiptEventAndSequence(){
        source(true);
        tx(()->service.appendMessage("TENANT_A",sessionNo,1,null,1,"synthetic conflict",1,"agent-ticket-notice:"+run));
        var boundary=new AgentTicketCreations(jdbc,tickets,service);
        var permit=AgentActionPermitFixtures.noticePermit(args,Instant.now().plusSeconds(4));
        assertEquals(40906,assertThrows(BizException.class,()->tx(()->boundary.create(permit,args))).getCode());
        tx(()->{
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ticket WHERE source_session_id=?",Integer.class,sessionId));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM agent_ticket_receipt WHERE run_id=?",Integer.class,run));
            assertEquals(3,jdbc.queryForObject("SELECT ticket_count FROM customer WHERE id=?",Integer.class,customerId));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_event WHERE customer_id=? AND event_type=10",Integer.class,customerId));
            assertEquals(2L,jdbc.queryForObject("SELECT last_msg_seq FROM session WHERE id=?",Long.class,sessionId));
            var history=service.messagesAfter("TENANT_A",sessionNo,1L,10,false);
            assertEquals(1,history.size());
            assertEquals("synthetic conflict",history.getFirst().content());
            return null;
        });
    }
    @Test void exactUnicodeTitleAndMaximumUtf8BodyAreStoredWithoutSilentTruncation(){
        source(false);args=new AgentTicketArguments(args.tenant(),run,event.eventId(),args.snapshotId(),"🙂".repeat(128),"中".repeat(2730)+"xx");
        var receipt=create();facts(1,1);
        tx(()->{assertEquals(args.title(),jdbc.queryForObject("SELECT title FROM ticket WHERE id=?",String.class,receipt.ticketId()));
            assertEquals(args.content(),jdbc.queryForObject("SELECT \"desc\" FROM ticket WHERE id=?",String.class,receipt.ticketId()));return null;});
    }
    @Test void legacyChannelCreationKeepsItsOriginalActorAndHasNoAgentReceipt(){
        source(true);var result=tx(()->tickets.createFromChannel("TENANT_A","synthetic channel",new ChannelTicketCommand("legacy title","legacy body",5,2,customerId,null,1,sessionNo,sessionId,null,null)));
        tx(()->{assertEquals("channel",jdbc.queryForObject("SELECT creator FROM ticket WHERE ticket_no=?",String.class,result.ticketNo()));return null;});facts(1,0);
    }
    @Test void eightConcurrentSameRunCallsCreateOneTicketEventCounterAndReceipt()throws Exception{
        source(true);var permit=permit();var barrier=new CyclicBarrier(8);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var futures=new ArrayList<Future<AgentTicketCreations.Receipt>>();
            for(int i=0;i<8;i++)futures.add(executor.submit(()->{barrier.await(10,TimeUnit.SECONDS);return tx(()->creations.create(permit,args));}));
            var expected=futures.getFirst().get(15,TimeUnit.SECONDS);for(var future:futures)assertEquals(expected,future.get(15,TimeUnit.SECONDS));
        }
        facts(1,1);tx(()->{assertEquals(4,jdbc.queryForObject("SELECT ticket_count FROM customer WHERE id=?",Integer.class,customerId));return null;});
    }
    @Test void changedArgumentsCannotReplaceExistingCreation(){
        source(true);var first=create();var changed=new AgentTicketArguments(args.tenant(),run,event.eventId(),args.snapshotId(),"changed",args.content());
        var permit=AgentActionPermitFixtures.permit(changed,Instant.now().plusSeconds(4));
        assertEquals(40906,assertThrows(BizException.class,()->tx(()->creations.create(permit,changed))).getCode());
        assertEquals(first,tx(()->creations.find(args)));facts(1,1);
    }
    @Test void missingPermitAndReplyPermitCannotExecuteTicketService(){
        source(true);assertEquals(40906,assertThrows(BizException.class,()->tx(()->creations.create(null,args))).getCode());
        var replyPermit=AgentActionPermitFixtures.permit(args,Instant.now().plusSeconds(4),AgentActionPermit.Operation.SESSION_REPLY);
        assertEquals(40906,assertThrows(BizException.class,()->tx(()->creations.create(replyPermit,args))).getCode());facts(0,0);
        verify(tickets,never()).createForAgent(anyString(),anyString(),any(),any());
    }
    @Test void incorrectSnapshotIsRejectedBeforeCallingBusinessService(){
        source(true);var wrong=new AgentTicketArguments(args.tenant(),run,event.eventId(),UUID.randomUUID(),args.title(),args.content());
        var permit=AgentActionPermitFixtures.permit(wrong,Instant.now().plusSeconds(4));
        assertEquals(40906,assertThrows(BizException.class,()->tx(()->creations.create(permit,wrong))).getCode());facts(0,0);
        verify(tickets,never()).createForAgent(anyString(),anyString(),any(),any());
    }
    @Test void takeoverClosedOrChangedCurrentSourceBlocksBeforeBusinessCreation(){
        source(true);admin("UPDATE session SET agent_id=12345 WHERE id="+sessionId);
        assertEquals(40402,assertThrows(BizException.class,this::create).getCode());
        admin("UPDATE session SET agent_id=NULL,status=3 WHERE id="+sessionId);assertEquals(40402,assertThrows(BizException.class,this::create).getCode());
        admin("UPDATE session SET status=2 WHERE id="+sessionId+"; UPDATE session_message SET content='changed' WHERE session_id="+sessionId);
        assertEquals(40902,assertThrows(BizException.class,this::create).getCode());facts(0,0);
    }
    @Test void deletedOrForeignAssociatedCustomerIsNotAdoptedFromSessionReference(){
        source(true);admin("UPDATE customer SET is_deleted=true WHERE id="+customerId);
        assertEquals(40406,assertThrows(BizException.class,this::create).getCode());
        admin("UPDATE customer SET is_deleted=false,tenant_code='TENANT_B' WHERE id="+customerId);
        assertEquals(40406,assertThrows(BizException.class,this::create).getCode());facts(0,0);
    }
    @Test void receiptReadConfirmsPastCommitAfterTakeoverWithoutCurrentStatusOrNewExecution(){
        source(true);var first=create();admin("UPDATE session SET agent_id=12345 WHERE id="+sessionId);
        assertEquals(first,tx(()->creations.get("TENANT_A",event.eventId(),run)));
        assertNull(tx(()->creations.get("TENANT_A",UUID.randomUUID(),run)));
        assertNull(TenantContext.bind("TENANT_B",()->transaction.execute(status->creations.get("TENANT_B",event.eventId(),run))));facts(1,1);
        verify(tickets,times(1)).createForAgent(anyString(),anyString(),any(),eq(run));
    }
    @Test void receiptInsertFailureRollsBackTicketTimelineDynamicsAndCustomerCount(){
        source(true);admin("REVOKE INSERT ON agent_ticket_receipt FROM supportflow_customer_tenant");
        try{assertThrows(DataAccessException.class,this::create);}finally{admin("GRANT INSERT ON agent_ticket_receipt TO supportflow_customer_tenant");}
        facts(0,0);tx(()->{assertEquals(3,jdbc.queryForObject("SELECT ticket_count FROM customer WHERE id=?",Integer.class,customerId));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_event WHERE customer_id=?",Integer.class,customerId));return null;});
    }
    @Test void mismatchedActualTicketAfterServiceReturnIsRejectedAndRolledBack(){
        source(true);var reached=new AtomicBoolean();
        doAnswer(invocation->{var result=(io.supportflow.customer.dto.ticket.TicketVO)invocation.callRealMethod();reached.set(true);
            jdbc.update("UPDATE ticket SET title='tampered title' WHERE ticket_no=?",result.ticketNo());return result;
        }).when(tickets).createForAgent(anyString(),anyString(),any(),eq(run));
        assertEquals(42206,assertThrows(BizException.class,this::create).getCode());assertTrue(reached.get());facts(0,0);
    }
    @Test void sourceExpiryAfterActualBusinessWritesRollsBackWholeCreation(){
        source(true);var reached=new AtomicBoolean();
        doAnswer(invocation->{var result=invocation.callRealMethod();reached.set(true);
            admin("UPDATE agent_source_snapshot SET created_at=clock_timestamp()-interval '2 hours',expires_at=clock_timestamp()-interval '1 hour' WHERE event_id='"+event.eventId()+"'");return result;
        }).when(tickets).createForAgent(anyString(),anyString(),any(),eq(run));
        assertEquals(41002,assertThrows(BizException.class,this::create).getCode());assertTrue(reached.get());facts(0,0);
    }
    @Test void expiredPermitBeforeInvocationHasNoBusinessEffect(){
        source(true);var expired=AgentActionPermitFixtures.permit(args,Instant.now().minusMillis(1));
        assertEquals(40906,assertThrows(BizException.class,()->tx(()->creations.create(expired,args))).getCode());facts(0,0);
        verify(tickets,never()).createForAgent(anyString(),anyString(),any(),any());
    }
    @Test void latePermitExpiryAfterActualTicketAndReceiptWritesRollsBackAll(){
        source(true);var permit=permit();var reached=new AtomicBoolean();
        doAnswer(invocation->{var result=invocation.callRealMethod();reached.set(true);
            long delay=permit.expiresAt().toEpochMilli()-Instant.now().toEpochMilli()+20;if(delay>0)Thread.sleep(delay);return result;
        }).when(tickets).createForAgent(anyString(),anyString(),any(),eq(run));
        assertEquals(40906,assertThrows(BizException.class,()->tx(()->creations.create(permit,args))).getCode());assertTrue(reached.get());facts(0,0);
    }
}
