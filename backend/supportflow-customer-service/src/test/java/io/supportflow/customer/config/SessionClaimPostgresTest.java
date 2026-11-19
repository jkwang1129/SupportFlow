package io.supportflow.customer.config;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import io.supportflow.common.context.TenantContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.entity.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@EnabledIfEnvironmentVariable(named = "SUPPORTFLOW_RLS_TEST_URL", matches = ".+")
class SessionClaimPostgresTest extends SessionPostgresFixture {
    @Test
    void twoAgentsHaveOneWinnerAndOnlyOneAssignmentEventAndSystemMessage() throws Exception {
        synchronizeInitialReads();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> claimOrConflict(101L));
            var second = workers.submit(() -> claimOrConflict(102L));
            var results = Arrays.asList(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(result -> result != null).count());
            long winner = results.stream().filter(result -> result != null).findFirst().orElseThrow();
            assertAssignment(winner, 1, 1);
        }
    }

    @Test
    void simultaneousRetriesByTheSameAgentReturnTheSameAssignmentWithoutNewEffects() throws Exception {
        synchronizeInitialReads();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> claim(101L));
            var second = workers.submit(() -> claim(101L));
            assertEquals(101L, first.get(15, TimeUnit.SECONDS).getAgentId());
            assertEquals(101L, second.get(15, TimeUnit.SECONDS).getAgentId());
        }
        assertAssignment(101L, 1, 1);
    }

    @Test
    void closedSessionCannotBeClaimedOrReopened() {
        scoped(() -> jdbc.update("UPDATE session SET status=4 WHERE id=?", sessionId));
        assertThrows(BizException.class, () -> claim(101L));
        assertAssignment(null, 0, 0);
        scoped(() -> { assertEquals(4, jdbc.queryForObject("SELECT status FROM session WHERE id=?", Integer.class, sessionId)); return null; });
    }

    @Test
    void closingBetweenReadAndAssignmentCannotResurrectTheSession() {
        doAnswer(call -> {
            Session previous = realSessions.selectOne(call.getArgument(0));
            try (var closer = Executors.newSingleThreadExecutor()) {
                closer.submit(() -> scoped(() -> jdbc.update("UPDATE session SET status=4 WHERE id=?", sessionId)))
                        .get(5, TimeUnit.SECONDS);
            }
            return previous;
        }).when(sessions).selectOne(any(Wrapper.class));
        assertThrows(BizException.class, () -> claim(101L));
        assertAssignment(null, 0, 0);
    }

    @Test
    void invalidAgentIdsAndForeignTenantAreRejectedWithoutEffects() {
        for (Long invalid : Arrays.asList(null, 0L, -1L)) {
            assertThrows(BizException.class, () -> claim(invalid));
        }
        assertThrows(BizException.class, () -> TenantContext.bind("TENANT_B", () -> transaction.execute(
                status -> service.claimSession("TENANT_B", sessionNo, 101L))));
        assertAssignment(null, 0, 0);
    }

    @SuppressWarnings("unchecked")
    private void synchronizeInitialReads() {
        var barrier = new CyclicBarrier(2);
        var reads = new AtomicInteger();
        doAnswer(call -> {
            var previous = realSessions.selectOne((Wrapper<Session>) call.getArgument(0));
            if (reads.incrementAndGet() <= 2) barrier.await(5, TimeUnit.SECONDS);
            return previous;
        }).when(sessions).selectOne(any(Wrapper.class));
    }

    private Session claim(Long agentId) {
        return scoped(() -> transaction.execute(status -> service.claimSession("TENANT_A", sessionNo, agentId)));
    }

    private Long claimOrConflict(Long agentId) {
        try { return claim(agentId).getAgentId(); }
        catch (BizException conflict) { return null; }
    }

    private void assertAssignment(Long agent, int events, int messages) {
        scoped(() -> {
            assertEquals(agent, jdbc.queryForObject("SELECT agent_id FROM session WHERE id=?", Long.class, sessionId));
            assertEquals(events, jdbc.queryForObject("SELECT count(*) FROM session_event WHERE session_id=? AND event_type=3", Integer.class, sessionId));
            assertEquals(messages, jdbc.queryForObject("SELECT count(*) FROM session_message WHERE session_id=? AND sender_type=4", Integer.class, sessionId));
            return null;
        });
    }
}
