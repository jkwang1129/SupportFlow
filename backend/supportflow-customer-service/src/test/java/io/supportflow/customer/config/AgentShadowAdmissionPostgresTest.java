package io.supportflow.customer.config;

import io.supportflow.customer.dto.session.MessageVO;
import io.supportflow.customer.event.SessionMessageEvent;
import io.supportflow.customer.service.bot.AgentDispatchSchedule;
import io.supportflow.customer.service.bot.AgentEventOutbox;
import io.supportflow.customer.service.bot.AgentShadowAdmission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SUPPORTFLOW_RLS_TEST_URL", matches = ".+")
class AgentShadowAdmissionPostgresTest extends SessionPostgresFixture {
    private AgentDispatchSchedule schedule;
    private AgentEventOutbox outbox;
    private AgentShadowAdmission admission;

    @BeforeEach void enableShadowAdmission() {
        outbox = new AgentEventOutbox(jdbc);
        schedule = new AgentDispatchSchedule(outbox, transaction.getTransactionManager(), "TENANT_A", true,
                "http://127.0.0.1:14000", "disposable-service-key-00000000000000000000");
        admission = new AgentShadowAdmission(outbox, transaction.getTransactionManager(), schedule);
        onMessage = admission::record;
    }

    @AfterEach @Override void cleanup() {
        try {
            if (schedule != null) schedule.stop();
            if (jdbc != null) {
                try (var connection = DriverManager.getConnection(System.getenv("SUPPORTFLOW_RLS_TEST_URL"), "supportflow",
                        System.getenv("SUPPORTFLOW_RLS_TEST_MIGRATION_PASSWORD"));
                     var delete = connection.prepareStatement("DELETE FROM agent_event_outbox WHERE session_id=?")) {
                    delete.setLong(1, sessionId);
                    delete.executeUpdate();
                } catch (Exception failure) { throw new IllegalStateException("Disposable fixture cleanup failed", failure); }
            }
        } finally {
            super.cleanup();
        }
    }

    private MessageVO append(int sender, int type, int visible, String text, String key) {
        return scoped(() -> transaction.execute(status ->
                service.appendMessage("TENANT_A", sessionNo, sender, null, type, text, visible, key)));
    }

    private AgentEventOutbox.Event registered(MessageVO message) {
        return scoped(() -> transaction.execute(status -> outbox.getByMessage("TENANT_A", Long.parseLong(message.msgId()))));
    }

    @Test void committedVisitorTextHasOneMatchingEventAndDuplicateReturnsOriginal() {
        var original = append(1, 1, 1, "synthetic visitor text", "shadow-repeat");
        var event = registered(original);
        assertNotNull(event);
        assertEquals("PENDING", event.state());
        assertEquals(sessionNo, event.sessionNo());
        assertEquals(Long.parseLong(original.msgId()), event.messageId());
        assertEquals("runtime-v1", event.runtimeVersion());
        assertEquals(original.msgId(), append(1, 1, 1, "synthetic duplicate body", "shadow-repeat").msgId());
        assertEquals(event, registered(original));
        assertEquals(1, committedEvents.get());
    }

    @Test void outboxAndMessageRollBackTogether() {
        var rolledBack = scoped(() -> transaction.execute(status -> {
            var inserted = service.appendMessage("TENANT_A", sessionNo, 1, null, 1, "synthetic rolled back", 1, "shadow-rollback");
            assertNotNull(outbox.getByMessage("TENANT_A", Long.parseLong(inserted.msgId())));
            status.setRollbackOnly();
            return inserted;
        }));
        assertNull(registered(rolledBack));
        assertEquals(0, scoped(() -> jdbc.queryForObject("SELECT count(*) FROM session_message WHERE id=?",
                Integer.class, Long.parseLong(rolledBack.msgId()))));
        assertEquals(0, committedEvents.get());
    }

    @Test void onlySharedVisitorTextInAnUnassignedSessionIsEnrolled() {
        assertNull(registered(append(2, 1, 1, "synthetic agent text", "shadow-agent")));
        assertNull(registered(append(1, 2, 1, "synthetic image", "shadow-image")));
        assertNull(registered(append(1, 1, 2, "synthetic private note", "shadow-private")));
        assertNull(registered(append(1, 1, 1, "值".repeat(2731), "shadow-oversized")));
        scoped(() -> jdbc.update("UPDATE session SET agent_id=99 WHERE id=?", sessionId));
        assertNull(registered(append(1, 1, 1, "synthetic assigned", "shadow-takeover")));
    }

    @Test void transientOutboxLockFailureDoesNotRollBackTheVisitorMessage() throws Exception {
        try (var lock = pool.getConnection(); var statement = lock.createStatement()) {
            lock.setAutoCommit(false);
            statement.execute("SELECT pg_advisory_xact_lock(hashtextextended('agent-admit:TENANT_A',0))");
            var message = append(1, 1, 1, "synthetic message during contention", "shadow-lock");
            assertNull(registered(message));
            assertEquals(1, scoped(() -> jdbc.queryForObject("SELECT count(*) FROM session_message WHERE id=?",
                    Integer.class, Long.parseLong(message.msgId()))));
            assertEquals(1, committedEvents.get());
            lock.rollback();
        }
    }

    @Test void anotherTenantOrUntrustedEventThreadCannotEnrollAnExistingMessage() {
        onMessage = event -> {};
        var message = append(1, 1, 1, "synthetic before test", "shadow-unbound");
        var reference = new SessionMessageEvent("TENANT_A", sessionNo, 1, 1, "synthetic text", 1, Long.parseLong(message.msgId()));
        admission.record(reference);
        scoped(() -> transaction.execute(status -> {
            admission.record(new SessionMessageEvent("TENANT_B", sessionNo, 1, 1, "synthetic text", 1, Long.parseLong(message.msgId())));
            return null;
        }));
        assertNull(registered(message));
    }
}
