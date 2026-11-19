package io.supportflow.customer.config;

import io.supportflow.common.context.TenantContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.service.bot.AgentEventOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;

import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SUPPORTFLOW_RLS_TEST_URL", matches=".+")
class AgentEventOutboxPostgresTest extends SessionPostgresFixture {
    private AgentEventOutbox outbox;
    private final java.util.List<Long> extraSessions=new java.util.ArrayList<>();

    @BeforeEach void createOutbox() { outbox = new AgentEventOutbox(jdbc); }

    private <T> T tx(Supplier<T> action) { return scoped(() -> transaction.execute(status -> action.get())); }
    private AgentEventOutbox.Event append(String key) {
        return tx(() -> {
            var message = service.appendMessage("TENANT_A",sessionNo,1,null,1,"synthetic-private-message",1,key);
            return outbox.enqueue("TENANT_A",Long.parseLong(message.msgId()),"runtime-v1",500);
        });
    }
    private AgentEventOutbox.Event claim(String owner) { return tx(() -> outbox.claim("TENANT_A",owner,30)); }

    @Override @AfterEach void cleanup() {
        try {
            admin("DELETE FROM agent_event_outbox WHERE session_id=" + sessionId);
            for(long id:extraSessions) admin("DELETE FROM agent_event_outbox WHERE session_id="+id+"; DELETE FROM session_message WHERE session_id="+id+"; DELETE FROM session WHERE id="+id);
        }
        finally { super.cleanup(); }
    }
    private void admin(String sql) {
        try (var connection=DriverManager.getConnection(System.getenv("SUPPORTFLOW_RLS_TEST_URL"),"supportflow",
                System.getenv("SUPPORTFLOW_RLS_TEST_MIGRATION_PASSWORD")); var statement=connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception failure) { throw new IllegalStateException("Disposable fixture administration failed",failure); }
    }

    @Test void sourceMessageAndEventCommitOrRollBackTogetherAndDuplicatesRemainStable() {
        scoped(() -> transaction.execute(status -> {
            var message=service.appendMessage("TENANT_A",sessionNo,1,null,1,"synthetic-private-message",1,"rollback");
            outbox.enqueue("TENANT_A",Long.parseLong(message.msgId()),"runtime-v1",500);
            status.setRollbackOnly(); return null;
        }));
        tx(() -> {
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM agent_event_outbox WHERE session_id=?",Integer.class,sessionId));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM session_message WHERE session_id=?",Integer.class,sessionId));
            return null;
        });
        var event=append("rollback");
        assertEquals(event,append("rollback"));
        assertFalse(event.toString().contains("synthetic-private-message"));
        assertEquals("visitor-session:"+sessionId,event.principalRef());
        assertEquals(event,tx(() -> new AgentEventOutbox(jdbc).getByMessage("TENANT_A",event.messageId())));
        assertThrows(BizException.class,() -> tx(() -> outbox.enqueue("TENANT_A",event.messageId(),"changed-version",500)));
    }

    @Test void concurrentDuplicateEventsPersistExactlyOneSourceEvent() throws Exception {
        var start=new CountDownLatch(1);
        try (var threads=Executors.newFixedThreadPool(6)) {
            var futures=new java.util.ArrayList<Future<AgentEventOutbox.Event>>();
            for(int i=0;i<6;i++) futures.add(threads.submit(() -> { start.await(); return append("duplicate"); }));
            start.countDown();
            var events=new java.util.HashSet<UUID>();
            for(var future:futures) events.add(future.get(20,TimeUnit.SECONDS).eventId());
            assertEquals(1,events.size());
        }
    }

    @Test void admissionRequiresTrustedTenantAndExistingTransaction() {
        assertThrows(IllegalStateException.class,() -> outbox.enqueue("TENANT_A",1,"runtime-v1",500));
        assertThrows(IllegalStateException.class,() -> transaction.execute(status -> outbox.claim("TENANT_A","worker",30)));
        assertThrows(IllegalStateException.class,() -> TenantContext.bind("TENANT_B",() -> transaction.execute(status -> outbox.claim("TENANT_A","worker",30))));
        assertThrows(IllegalStateException.class,() -> TenantContext.bind("PLATFORM",() -> transaction.execute(status -> outbox.claim("PLATFORM","worker",30))));
    }

    @Test void sourceMustBeAnOwnedVisibleVisitorMessage() {
        assertThrows(BizException.class,() -> tx(() -> outbox.enqueue("TENANT_A",99,"runtime-v1",500)));
        for(int sender: new int[]{2,3,4}) {
            assertThrows(BizException.class,() -> tx(() -> {
                var message=service.appendMessage("TENANT_A",sessionNo,sender,null,1,"synthetic",1,"sender-"+sender);
                return outbox.enqueue("TENANT_A",Long.parseLong(message.msgId()),"runtime-v1",500);
            }));
        }
        var event=append("owned");
        assertThrows(BizException.class,() -> TenantContext.bind("TENANT_B",() -> transaction.execute(status -> outbox.enqueue("TENANT_B",event.messageId(),"runtime-v1",500))));
        assertThrows(BizException.class,() -> tx(() -> {
            var message=service.appendMessage("TENANT_A",sessionNo,1,null,1,"private",2,"private");
            return outbox.enqueue("TENANT_A",Long.parseLong(message.msgId()),"runtime-v1",500);
        }));
    }

    @Test void orderedDispatchWaitsForEarlierReceiptAndValidatesReceiptIdentity() {
        var first=append("one"); var second=append("two");
        var lease=claim("worker-1"); assertEquals(first.eventId(),lease.eventId());
        assertNull(claim("worker-2"));
        assertThrows(IllegalArgumentException.class,() -> tx(() -> { outbox.acknowledge(lease,UUID.randomUUID(),UUID.randomUUID()); return null; }));
        var run=UUID.randomUUID();
        tx(() -> { outbox.acknowledge(lease,lease.eventId(),run); return null; });
        assertEquals(run,tx(() -> outbox.getByMessage("TENANT_A",first.messageId())).runId());
        assertEquals(second.eventId(),claim("worker-2").eventId());
    }

    @Test void expiredDeliveryRetainsEventIdAndFencesOldAcknowledgementAndRetry() {
        var event=append("lost-response"); var old=claim("worker-1");
        admin("UPDATE agent_event_outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE event_id='"+old.eventId()+"'");
        assertThrows(IllegalStateException.class,() -> tx(() -> { outbox.acknowledge(old,old.eventId(),UUID.randomUUID()); return null; }));
        var current=claim("worker-2");
        assertEquals(event.eventId(),current.eventId()); assertEquals(2,current.attempts()); assertTrue(current.fence()>old.fence());
        assertThrows(IllegalStateException.class,() -> tx(() -> { outbox.retry(old,"TIMEOUT",0); return null; }));
        tx(() -> { outbox.acknowledge(current,current.eventId(),UUID.randomUUID()); return null; });
    }

    @Test void concurrentDispatchersClaimOneEventOnly() throws Exception {
        append("one-winner");
        var start=new CyclicBarrier(6);
        try (var threads=Executors.newFixedThreadPool(6)) {
            var futures=new java.util.ArrayList<Future<AgentEventOutbox.Event>>();
            for(int i=0;i<6;i++) {
                final int index=i;
                futures.add(threads.submit(() -> { start.await(5,TimeUnit.SECONDS); return claim("worker-"+index); }));
            }
            int winners=0;
            for(var future:futures) if(future.get(10,TimeUnit.SECONDS)!=null) winners++;
            assertEquals(1,winners);
        }
    }

    @Test void exhaustedLostResponsesAreHeldAndOtherSessionsStillProgress() {
        var first=append("held"); append("following");
        for(int i=1;i<=8;i++) {
            var lease=claim("lost-response"); assertEquals(first.eventId(),lease.eventId());
            admin("UPDATE agent_event_outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE event_id='"+lease.eventId()+"'");
        }
        assertNull(claim("after-losses"));
        assertEquals("HELD",tx(() -> outbox.getByMessage("TENANT_A",first.messageId())).state());
        long other=IDS.incrementAndGet(); extraSessions.add(other);
        String number="INDEPENDENT_"+other;
        admin("INSERT INTO session(id,tenant_code,session_no,channel_id,status,start_time) VALUES("+other+",'TENANT_A','"+number+"',900001,2,CURRENT_TIMESTAMP)");
        var independent=tx(() -> {
            var message=service.appendMessage("TENANT_A",number,1,null,1,"synthetic",1,"independent");
            return outbox.enqueue("TENANT_A",Long.parseLong(message.msgId()),"runtime-v1",500);
        });
        assertEquals(independent.eventId(),claim("other-session").eventId());
    }

    @Test void malformedTraceIsReplacedWithBoundedOpaqueReference() {
        var event=io.supportflow.common.context.TraceContext.bind("untrusted header with whitespace",() -> append("trace"));
        assertTrue(event.traceId().matches("[a-f0-9]{16}"));
    }

    @Test void explicitRetryPreservesEventAndStopsAtFiniteDeliveryBudget() {
        var first=append("first"); append("second");
        for(int i=1;i<=8;i++) {
            var lease=claim("worker"); assertEquals(first.eventId(),lease.eventId()); assertEquals(i,lease.attempts());
            tx(() -> { outbox.retry(lease,"NETWORK_ERROR",0); return null; });
        }
        assertNull(claim("after-budget"));
        assertEquals("HELD",tx(() -> outbox.getByMessage("TENANT_A",first.messageId())).state());
    }

    @Test void admissionBackpressureRollsBackNewMessageAndDuplicatesConsumeNoExtraSlot() {
        var first=append("one");
        assertEquals(first.eventId(),tx(() -> outbox.enqueue("TENANT_A",first.messageId(),"runtime-v1",1)).eventId());
        assertThrows(BizException.class,() -> tx(() -> {
            var message=service.appendMessage("TENANT_A",sessionNo,1,null,1,"must-rollback",1,"full");
            return outbox.enqueue("TENANT_A",Long.parseLong(message.msgId()),"runtime-v1",1);
        }));
        assertEquals(1,tx(() -> jdbc.queryForObject("SELECT count(*) FROM session_message WHERE session_id=?",Integer.class,sessionId)));
    }

    @Test void rlsAndDatabasePrivilegesRejectUnscopedForeignAndDestructiveAccess() {
        var event=append("own");
        assertNull(TenantContext.bind("TENANT_B",() -> transaction.execute(status -> outbox.getByMessage("TENANT_B",event.messageId()))));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM agent_event_outbox",Integer.class));
        for(String statement: new String[]{"DELETE FROM agent_event_outbox","TRUNCATE agent_event_outbox","ALTER TABLE agent_event_outbox DISABLE ROW LEVEL SECURITY"})
            assertThrows(DataAccessException.class,() -> tx(() -> jdbc.update(statement)));
        admin("CREATE POLICY outbox_fault_permissive ON agent_event_outbox USING(true) WITH CHECK(true)");
        try { assertEquals(0,TenantContext.bind("TENANT_B",() -> jdbc.queryForObject("SELECT count(*) FROM agent_event_outbox",Integer.class))); }
        finally { admin("DROP POLICY outbox_fault_permissive ON agent_event_outbox"); }
    }
}
