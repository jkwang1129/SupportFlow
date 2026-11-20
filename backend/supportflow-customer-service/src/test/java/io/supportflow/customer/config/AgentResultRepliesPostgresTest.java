package io.supportflow.customer.config;

import io.supportflow.common.context.TenantContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.service.bot.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real SessionService/mappers and restricted PostgreSQL; permit signing is synthetic in this storage unit. */
@EnabledIfEnvironmentVariable(named="SUPPORTFLOW_RLS_TEST_URL", matches=".+")
class AgentResultRepliesPostgresTest extends SessionPostgresFixture {
    private AgentResultReplies replies;
    private AgentReplyArguments reply;
    private AgentEventOutbox.Event event;
    private final UUID run = UUID.randomUUID();
    private <T> T tx(Supplier<T> call) { return scoped(() -> transaction.execute(status -> call.get())); }
    @BeforeEach void prepareResult() {
        var snapshots = new AgentSourceSnapshots(jdbc);
        var outbox = new AgentEventOutbox(jdbc);
        replies = new AgentResultReplies(jdbc, snapshots, service);
        reply = tx(() -> {
            var message = service.appendMessage("TENANT_A", sessionNo, 1, null, 1, "synthetic source", 1, "reply-source");
            event = outbox.enqueue("TENANT_A", Long.parseLong(message.msgId()), "runtime-v1", 500);
            var lease = outbox.claim("TENANT_A", "reply-test", 30);
            outbox.acknowledge(lease, event.eventId(), run);
            var source = snapshots.capture("TENANT_A", event.eventId(), run);
            return new AgentReplyArguments("TENANT_A", run, event.eventId(), source.snapshotId(), "synthetic bot reply\n原始文本");
        });
        clearInvocations(billing, brain); committedEvents.set(0);
    }
    private AgentActionPermit permit() { return AgentActionPermitFixtures.permit(reply, Instant.now().plusSeconds(4)); }
    private AgentResultReplies.Receipt append() { var permit = permit(); return tx(() -> replies.append(permit, reply)); }
    private void admin(String sql) {
        try (var connection = DriverManager.getConnection(System.getenv("SUPPORTFLOW_RLS_TEST_URL"), "supportflow", System.getenv("SUPPORTFLOW_RLS_TEST_MIGRATION_PASSWORD"));
             var statement = connection.createStatement()) { statement.execute(sql); }
        catch (Exception failure) { throw new IllegalStateException("Disposable result fixture administration failed", failure); }
    }
    @Override @AfterEach void cleanup() {
        try {
            admin("DELETE FROM agent_result_receipt WHERE event_id IN (SELECT event_id FROM agent_event_outbox WHERE session_id=" + sessionId + "); DELETE FROM agent_source_snapshot WHERE event_id IN (SELECT event_id FROM agent_event_outbox WHERE session_id=" + sessionId + "); DELETE FROM agent_event_outbox WHERE session_id=" + sessionId);
        } finally { super.cleanup(); }
    }
    private void facts(int added, int receipts) {
        tx(() -> {
            assertEquals(added+1, jdbc.queryForObject("SELECT count(*) FROM session_message WHERE session_id=?", Integer.class, sessionId));
            assertEquals((long)added+1, jdbc.queryForObject("SELECT last_msg_seq FROM session WHERE id=?", Long.class, sessionId));
            assertEquals(receipts, jdbc.queryForObject("SELECT count(*) FROM agent_result_receipt WHERE run_id=?", Integer.class, run));
            return null;
        });
    }

    @Test void replyAndReceiptCommitOnceAndReuseOriginalSessionServiceEffects() {
        assertNull(tx(() -> replies.find(reply)));
        var receipt = append(); assertEquals(2, receipt.sequence());
        assertEquals(receipt, tx(() -> replies.find(reply)));
        tx(() -> {
            var row = jdbc.queryForMap("SELECT sender_type,sender_id,msg_type,visible_to,content,client_msg_no FROM session_message WHERE id=?", receipt.messageId());
            assertEquals(3, row.get("sender_type")); assertNull(row.get("sender_id")); assertEquals(1, row.get("msg_type"));
            assertEquals(1, row.get("visible_to")); assertEquals(reply.content(), row.get("content")); assertEquals("agent-run:"+run, row.get("client_msg_no")); return null;
        });
        facts(1,1); verify(billing, times(1)).countMessage("TENANT_A");
        verify(brain, never()).handleCustomerMessageAsync(anyString(), anyString()); assertEquals(1, committedEvents.get());
    }

    @Test void eightConcurrentDuplicatesReturnOneMessageReceiptSequenceAndBillingEffect() throws Exception {
        var permit = permit(); var start = new CyclicBarrier(8);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0,8).mapToObj(i -> workers.submit(() -> { start.await(5, TimeUnit.SECONDS); return tx(() -> replies.append(permit, reply)); })).toList();
            var first = futures.getFirst().get(12, TimeUnit.SECONDS);
            for (var future : futures) assertEquals(first, future.get(12, TimeUnit.SECONDS));
        }
        facts(1,1); verify(billing, times(1)).countMessage("TENANT_A"); assertEquals(1, committedEvents.get());
    }

    @Test void committedResultCanBeAcknowledgedAfterPermitExpiryAndResourceRevocationWithoutNewWrite() {
        var first = append();
        tx(() -> { jdbc.update("UPDATE session SET status=4,agent_id=99 WHERE id=?", sessionId); return null; });
        var old = AgentActionPermitFixtures.permit(reply, Instant.now().minusSeconds(1));
        assertEquals(first, tx(() -> replies.append(old, reply))); facts(1,1);
        verify(billing, times(1)).countMessage("TENANT_A"); assertEquals(1, committedEvents.get());
    }

    @Test void changedContentOrSnapshotCannotReplaceACommittedReply() {
        append();
        for (var changed : List.of(new AgentReplyArguments(reply.tenant(),run,reply.eventId(),reply.snapshotId(),reply.content()+"changed"),
                new AgentReplyArguments(reply.tenant(),run,reply.eventId(),UUID.randomUUID(),reply.content()))) {
            var permit = AgentActionPermitFixtures.permit(changed, Instant.now().plusSeconds(4));
            assertEquals(40903, assertThrows(BizException.class, () -> tx(() -> replies.append(permit,changed))).getCode());
            assertThrows(BizException.class, () -> tx(() -> replies.find(changed)));
        }
        facts(1,1); verify(billing, times(1)).countMessage("TENANT_A");
    }

    @Test void rollbackRemovesReceiptMessageSequenceAndAllAfterCommitEffects() {
        var permit = permit();
        scoped(() -> transaction.execute(status -> { replies.append(permit,reply); status.setRollbackOnly(); return null; }));
        facts(0,0); verifyNoInteractions(billing); assertEquals(0,committedEvents.get());
        assertEquals(2,append().sequence()); facts(1,1);
    }

    @Test void expiredPermitIsRejectedUsingDatabaseTimeBeforeAnyMessage() {
        var old = AgentActionPermitFixtures.permit(reply, Instant.now().minusSeconds(1));
        assertEquals(40903, assertThrows(BizException.class, () -> tx(() -> replies.append(old,reply))).getCode());
        facts(0,0); verifyNoInteractions(billing); assertEquals(0,committedEvents.get());
    }

    @Test void expiredSourceSnapshotCannotBeRefreshedByAReply() {
        admin("UPDATE agent_source_snapshot SET created_at=clock_timestamp()-interval '2 hours',expires_at=clock_timestamp()-interval '1 hour' WHERE event_id='"+event.eventId()+"'");
        assertEquals(41002,assertThrows(BizException.class,this::append).getCode());
        facts(0,0); verifyNoInteractions(billing); assertEquals(0,committedEvents.get());
    }

    @Test void databaseForeignKeysRejectMismatchedSnapshotsAndAnotherTenantsMessage() {
        long foreignSession=IDS.incrementAndGet(),foreignMessage=IDS.incrementAndGet();
        TenantContext.bind("TENANT_B",()->transaction.execute(status->{
            jdbc.update("INSERT INTO session(id,tenant_code,session_no,channel_id,status,start_time) VALUES(?,'TENANT_B',?,900002,2,CURRENT_TIMESTAMP)",foreignSession,"REPLY_FOREIGN_"+foreignSession);
            jdbc.update("INSERT INTO session_message(id,tenant_code,session_id,msg_no,seq,msg_type,sender_type,content,send_time) VALUES(?,'TENANT_B',?,?,1,1,3,'foreign',CURRENT_TIMESTAMP)",foreignMessage,foreignSession,"REPLY_FOREIGN_MSG_"+foreignMessage);
            return null;
        }));
        try {
            assertState("23503",()->rawReceipt("TENANT_A",reply.snapshotId(),foreignMessage));
            assertState("23503",()->rawReceipt("TENANT_A",UUID.randomUUID(),event.messageId()));
            assertState("42501",()->rawReceipt("TENANT_B",reply.snapshotId(),foreignMessage));
        } finally {
            TenantContext.bind("TENANT_B",()->transaction.execute(status->{
                jdbc.update("DELETE FROM session_message WHERE id=?",foreignMessage);
                jdbc.update("DELETE FROM session WHERE id=?",foreignSession);return null;
            }));
        }
        facts(0,0); verifyNoInteractions(billing);
    }

    private int rawReceipt(String tenant,UUID snapshot,long message) {
        return jdbc.update("""
                INSERT INTO agent_result_receipt(tenant_code,run_id,event_id,snapshot_id,arguments_sha256,operation,schema_version,
                step_key,step_no,fence,message_id,message_no,message_seq) VALUES(?,?,?,?,?,'session.reply','reply-v1','reply-1',1,1,?,'synthetic',1)
                """,tenant,run,event.eventId(),snapshot,reply.argumentsSha256(),message);
    }
    private void assertState(String expected,Supplier<?> call) {
        var failure=assertThrows(DataAccessException.class,()->tx(call::get));
        Throwable root=failure;while(root.getCause()!=null)root=root.getCause();assertEquals(expected,((SQLException)root).getSQLState());
    }

    @Test void expiryAfterMessageInsertionStillRollsBackEveryDatabaseChange() {
        var permit = permit(); var inserted = new AtomicBoolean();
        doAnswer(call -> {
            var saved = realMessages.insertClientMessage(call.getArgument(0)); inserted.set(true);
            long wait = permit.expiresAt().toEpochMilli()-System.currentTimeMillis()+30;
            if (wait>0) Thread.sleep(wait);
            return saved;
        }).when(messages).insertClientMessage(any());
        assertEquals(40903, assertThrows(BizException.class, () -> tx(() -> replies.append(permit,reply))).getCode());
        assertTrue(inserted.get()); facts(0,0); verifyNoInteractions(billing); assertEquals(0,committedEvents.get());
    }

    @Test void sessionTakeoverClosureHiddenDeletedOrChangedInputRevokeNewReplies() {
        for (String mutation : new String[]{"UPDATE session SET status=3 WHERE id="+sessionId,
                "UPDATE session SET status=4 WHERE id="+sessionId, "UPDATE session SET agent_id=99 WHERE id="+sessionId,
                "UPDATE session_message SET visible_to=2 WHERE id="+event.messageId(), "UPDATE session_message SET is_deleted=true WHERE id="+event.messageId(),
                "UPDATE session_message SET content='modified' WHERE id="+event.messageId(), "UPDATE session_message SET sender_type=3 WHERE id="+event.messageId()}) {
            tx(() -> { jdbc.update(mutation); return null; });
            assertThrows(BizException.class, this::append);
            tx(() -> { jdbc.update("UPDATE session SET status=2,agent_id=NULL WHERE id=?", sessionId);
                jdbc.update("UPDATE session_message SET visible_to=1,is_deleted=false,content='synthetic source',sender_type=1 WHERE id=?",event.messageId()); return null; });
        }
        facts(0,0); verifyNoInteractions(billing); assertEquals(0,committedEvents.get());
    }

    @Test void wrongScopeTransactionPermitOrSnapshotNeverWrites() {
        var permit = permit();
        assertThrows(IllegalStateException.class, () -> replies.append(permit,reply));
        assertThrows(IllegalStateException.class, () -> scoped(() -> replies.append(permit,reply)));
        assertThrows(IllegalStateException.class, () -> TenantContext.bind("TENANT_B", () -> transaction.execute(status -> replies.append(permit,reply))));
        var changed = new AgentReplyArguments(reply.tenant(),run,reply.eventId(),reply.snapshotId(),"changed");
        assertThrows(BizException.class, () -> tx(() -> replies.append(permit,changed)));
        var wrong = new AgentReplyArguments(reply.tenant(),run,reply.eventId(),UUID.randomUUID(),reply.content());
        var wrongPermit = AgentActionPermitFixtures.permit(wrong,Instant.now().plusSeconds(4));
        assertThrows(BizException.class, () -> tx(() -> replies.append(wrongPermit,wrong)));
        facts(0,0); verifyNoInteractions(billing);
    }

    @Test void anUnrelatedClientMessageCannotOccupyTheRuntimeReceiptKey() {
        tx(() -> service.appendMessage(reply.tenant(),sessionNo,1,null,1,reply.content(),1,"agent-run:"+run));
        clearInvocations(billing,brain); committedEvents.set(0);
        assertEquals(40903, assertThrows(BizException.class, this::append).getCode());
        facts(1,0); verifyNoInteractions(billing); assertEquals(0,committedEvents.get());
    }

    @Test void receiptInsertFailureRollsBackTheExistingBusinessServiceWrite() {
        admin("REVOKE INSERT ON agent_result_receipt FROM supportflow_customer_tenant");
        try { assertThrows(DataAccessException.class, this::append); }
        finally { admin("GRANT INSERT ON agent_result_receipt TO supportflow_customer_tenant"); }
        facts(0,0); verifyNoInteractions(billing); assertEquals(0,committedEvents.get());
    }

    @Test void receiptPermissionsAndRestrictiveRlsRemainFailClosed() {
        append();
        tx(() -> {
            assertTrue(jdbc.queryForObject("SELECT has_table_privilege(current_user,'agent_result_receipt','SELECT') AND has_table_privilege(current_user,'agent_result_receipt','INSERT')",Boolean.class));
            assertFalse(jdbc.queryForObject("SELECT has_table_privilege(current_user,'agent_result_receipt','UPDATE,DELETE,TRUNCATE')",Boolean.class)); return null;
        });
        for (String sql : new String[]{"UPDATE agent_result_receipt SET fence=2", "DELETE FROM agent_result_receipt", "TRUNCATE agent_result_receipt"}) {
            var failure = assertThrows(DataAccessException.class, () -> tx(() -> jdbc.update(sql)));
            Throwable root=failure; while(root.getCause()!=null) root=root.getCause(); assertEquals("42501",((SQLException)root).getSQLState());
        }
        admin("CREATE POLICY result_test_permissive ON agent_result_receipt USING(true) WITH CHECK(true)");
        try {
            Integer count=TenantContext.bind("TENANT_B", () -> transaction.execute(status -> jdbc.queryForObject("SELECT count(*) FROM agent_result_receipt WHERE run_id=?", Integer.class,run)));
            assertEquals(0,count.intValue());
        }
        finally { admin("DROP POLICY result_test_permissive ON agent_result_receipt"); }
    }
}
