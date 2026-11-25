package io.supportflow.customer.config;

import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.context.TenantContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.dto.bot.IndexResult;
import io.supportflow.customer.internal.KbAiClient;
import io.supportflow.customer.mapper.KbChunkMapper;
import io.supportflow.customer.mapper.KbDocumentMapper;
import io.supportflow.customer.service.bot.KbService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SUPPORTFLOW_RLS_TEST_URL", matches = ".+")
class KbDocumentLifecyclePostgresTest extends SessionPostgresFixture {
    private static final LoginUser EDITOR = new LoginUser(101, "U101", "editor", 2, "TENANT_A");
    private static final String OLD_POLICY = "退款在5个工作日内到账。";
    private static final String NEW_POLICY = "退款在2个工作日内到账。";
    private long documentId;
    private KbAiClient ai;
    private KbService knowledge;

    @BeforeEach
    void prepareDocument() {
        documentId = IDS.incrementAndGet();
        scoped(() -> jdbc.update("""
                INSERT INTO kb_document (id, tenant_code, doc_no, title, content, status, index_status, chunk_count)
                VALUES (?, 'TENANT_A', ?, '退款到账', ?, 1, 3, 1)
                """, documentId, "KB_LIFECYCLE_" + documentId, OLD_POLICY));
        replaceChunk(OLD_POLICY);
        ai = mock(KbAiClient.class);
        knowledge = new KbService(sql.getMapper(KbDocumentMapper.class), sql.getMapper(KbChunkMapper.class),
                null, null, ai, null, null);
    }

    @AfterEach
    void removeDocument() {
        if (documentId == 0) return;
        scoped(() -> {
            jdbc.update("DELETE FROM kb_chunk_stage WHERE doc_id=?", documentId);
            jdbc.update("DELETE FROM kb_chunk WHERE doc_id=?", documentId);
            return jdbc.update("DELETE FROM kb_document WHERE id=?", documentId);
        });
    }

    @Test
    void reindexReadsCommittedBodyAfterConcurrentEdit() throws Exception {
        var updated = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reindexPid = new CompletableFuture<Integer>();
        when(ai.indexText(eq("TENANT_A"), eq(documentId), any(UUID.class), anyString())).thenAnswer(call -> {
            String body = call.getArgument(3);
            if (updated.getCount() != 0) {
                updated.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS), "edit did not resume");
            }
            stageChunk(call.getArgument(2), body);
            return indexResult(body, 1);
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var edit = workers.submit(() -> tx(() -> knowledge.update(EDITOR, documentId, null, null, NEW_POLICY, null)));
            try {
                assertTrue(updated.await(5, TimeUnit.SECONDS), "edit did not reach indexing");
                var reindex = workers.submit(() -> tx(() -> {
                    reindexPid.complete(connectionPid());
                    return knowledge.reindex(EDITOR, documentId);
                }));
                awaitBlocked(reindexPid.get(5, TimeUnit.SECONDS));
                release.countDown();
                assertEquals(3, edit.get(8, TimeUnit.SECONDS).indexStatus());
                assertEquals(3, reindex.get(8, TimeUnit.SECONDS).indexStatus());
            } finally {
                release.countDown();
            }
        }
        assertEquals(NEW_POLICY, scoped(() -> jdbc.queryForObject(
                "SELECT content FROM kb_document WHERE id=?", String.class, documentId)));
        assertEquals(NEW_POLICY, scoped(() -> jdbc.queryForObject(
                "SELECT content FROM kb_chunk WHERE doc_id=?", String.class, documentId)));
        verify(ai, times(2)).indexText(eq("TENANT_A"), eq(documentId), any(UUID.class), eq(NEW_POLICY));
    }

    @Test
    void concurrentIndexFailurePreventsPublication() throws Exception {
        var changed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var publishPid = new CompletableFuture<Integer>();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var failure = workers.submit(() -> tx(() -> {
                jdbc.update("UPDATE kb_document SET index_status=4 WHERE id=?", documentId);
                changed.countDown();
                awaitRelease(release);
                return null;
            }));
            try {
                assertTrue(changed.await(5, TimeUnit.SECONDS), "index failure did not acquire the row");
                var publish = workers.submit(() -> tx(() -> {
                    publishPid.complete(connectionPid());
                    return knowledge.changeStatus(EDITOR, documentId, 3);
                }));
                awaitBlocked(publishPid.get(5, TimeUnit.SECONDS));
                release.countDown();
                failure.get(8, TimeUnit.SECONDS);
                var error = assertThrows(ExecutionException.class, () -> publish.get(8, TimeUnit.SECONDS));
                assertEquals(40001, assertInstanceOf(BizException.class, error.getCause()).getCode());
            } finally {
                release.countDown();
            }
        }
        assertEquals(1, scoped(() -> jdbc.queryForObject(
                "SELECT status FROM kb_document WHERE id=? AND index_status=4", Integer.class, documentId)));
    }

    @Test
    void losingIndexResponseKeepsPreviouslyPublishedBodyAndChunksAndRetryUsesItsOwnAttempt() {
        scoped(() -> jdbc.update("UPDATE kb_document SET status=3 WHERE id=?", documentId));
        var lostAttempt = new CompletableFuture<UUID>();
        var retryAttempt = new CompletableFuture<UUID>();
        when(ai.indexText(eq("TENANT_A"), eq(documentId), any(UUID.class), anyString())).thenAnswer(call -> {
            UUID attempt = call.getArgument(2);
            stageChunk(attempt, "先前等待超时的内容");
            lostAttempt.complete(attempt);
            throw new BizException(50001, "合成索引响应丢失");
        }).thenAnswer(call -> {
            UUID attempt = call.getArgument(2);
            stageChunk(attempt, NEW_POLICY);
            retryAttempt.complete(attempt);
            return indexResult(NEW_POLICY, 1);
        });
        assertThrows(BizException.class, () -> tx(() -> knowledge.update(EDITOR, documentId, null, null, "先前等待超时的内容", null)));
        assertEquals(OLD_POLICY, scoped(() -> jdbc.queryForObject(
                "SELECT content FROM kb_document WHERE id=? AND status=3 AND index_status=3", String.class, documentId)));
        assertEquals(OLD_POLICY, activeChunk());
        assertEquals(3, tx(() -> knowledge.update(EDITOR, documentId, null, null, NEW_POLICY, null)).indexStatus());
        assertNotEquals(lostAttempt.join(), retryAttempt.join());
        assertEquals(NEW_POLICY, activeChunk());
        assertEquals(List.of(lostAttempt.join()), scoped(() -> jdbc.queryForList(
                "SELECT attempt_id FROM kb_chunk_stage WHERE doc_id=?", UUID.class, documentId)));
    }

    @Test
    void aShortStagedBatchRollsBackDocumentAndActiveChunks() {
        when(ai.indexText(eq("TENANT_A"), eq(documentId), any(UUID.class), eq(NEW_POLICY))).thenAnswer(call -> {
            stageChunk(call.getArgument(2), NEW_POLICY);
            return indexResult(NEW_POLICY, 2);
        });
        var failure = assertThrows(BizException.class, () -> tx(() -> knowledge.update(EDITOR, documentId, null, null, NEW_POLICY, null)));
        assertEquals(50001, failure.getCode());
        assertEquals(OLD_POLICY, activeChunk());
        assertEquals(OLD_POLICY, scoped(() -> jdbc.queryForObject(
                "SELECT content FROM kb_document WHERE id=? AND index_status=3", String.class, documentId)));
        assertEquals(1, scoped(() -> jdbc.queryForObject("SELECT count(*) FROM kb_chunk_stage WHERE doc_id=?", Integer.class, documentId)));
    }

    @Test
    void expiredStagingCannotBecomeTheActiveIndexEvenWithAMatchingReceipt() {
        when(ai.indexText(eq("TENANT_A"), eq(documentId), any(UUID.class), eq(NEW_POLICY))).thenAnswer(call -> {
            stageChunk(call.getArgument(2), NEW_POLICY, 48);
            return indexResult(NEW_POLICY, 1);
        });
        var failure = assertThrows(BizException.class, () -> tx(() -> knowledge.update(EDITOR, documentId, null, null, NEW_POLICY, null)));
        assertEquals(50001, failure.getCode());
        assertEquals(OLD_POLICY, activeChunk());
        assertEquals(OLD_POLICY, scoped(() -> jdbc.queryForObject(
                "SELECT content FROM kb_document WHERE id=? AND index_status=3", String.class, documentId)));
        assertEquals(1, scoped(() -> jdbc.queryForObject("SELECT count(*) FROM kb_chunk_stage WHERE doc_id=?", Integer.class, documentId)));
    }

    @Test
    void foreignAndDeletedDocumentsCannotStartIndexingOrPublication() {
        var foreign = new LoginUser(102, "U102", "editor", 2, "TENANT_B");
        var denied = assertThrows(BizException.class, () -> TenantContext.bind("TENANT_B",
                () -> transaction.execute(status -> knowledge.reindex(foreign, documentId))));
        assertEquals(40401, denied.getCode());
        scoped(() -> jdbc.update("UPDATE kb_document SET is_deleted=TRUE WHERE id=?", documentId));
        var deleted = assertThrows(BizException.class, () -> tx(() -> knowledge.changeStatus(EDITOR, documentId, 3)));
        assertEquals(40401, deleted.getCode());
        verifyNoInteractions(ai);
    }

    @Test
    void deletingDocumentClearsActiveAndStagedChunksWithoutAnAiRequest() throws SQLException {
        stageChunk(UUID.randomUUID(), NEW_POLICY);
        tx(() -> {
            knowledge.delete(EDITOR, documentId);
            return null;
        });
        assertTrue(scoped(() -> jdbc.queryForObject(
                "SELECT is_deleted FROM kb_document WHERE id=?", Boolean.class, documentId)));
        assertEquals(0, scoped(() -> jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk WHERE doc_id=?", Integer.class, documentId)));
        assertEquals(0, scoped(() -> jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk_stage WHERE doc_id=?", Integer.class, documentId)));
        verifyNoInteractions(ai);
    }

    private <T> T tx(Supplier<T> action) {
        return scoped(() -> transaction.execute(status -> action.get()));
    }

    private String activeChunk() {
        return scoped(() -> jdbc.queryForObject("SELECT content FROM kb_chunk WHERE doc_id=?", String.class, documentId));
    }

    private IndexResult indexResult(String body, int count) {
        return new IndexResult(Long.toString(documentId), "", body.length(), count, 0, "fixture", "fixture", List.of());
    }

    private void stageChunk(UUID attempt, String body) throws SQLException {
        stageChunk(attempt, body, 0);
    }

    private void stageChunk(UUID attempt, String body, int ageHours) throws SQLException {
        try (var connection = DriverManager.getConnection(System.getenv("SUPPORTFLOW_RLS_TEST_URL"), "rls_test_kb",
                System.getenv("SUPPORTFLOW_RLS_TEST_KB_PASSWORD"))) {
            connection.setAutoCommit(false);
            try (var scope = connection.createStatement(); var insert = connection.prepareStatement("""
                    INSERT INTO kb_chunk_stage (id, tenant_code, doc_id, attempt_id, chunk_no, content, create_time)
                    VALUES (?, 'TENANT_A', ?, ?, 0, ?, statement_timestamp() - ? * interval '1 hour')
                    """)) {
                scope.execute("SELECT set_config('app.tenant_code', 'TENANT_A', true)");
                insert.setInt(1, IDS.incrementAndGet());
                insert.setLong(2, documentId);
                insert.setObject(3, attempt);
                insert.setString(4, body);
                insert.setInt(5, ageHours);
                insert.executeUpdate();
            }
            connection.commit();
        }
    }

    private void replaceChunk(String body) {
        tx(() -> {
            jdbc.update("DELETE FROM kb_chunk WHERE doc_id=?", documentId);
            return jdbc.update("""
                    INSERT INTO kb_chunk (id, tenant_code, doc_id, chunk_no, content)
                    VALUES (?, 'TENANT_A', ?, 0, ?)
                    """, documentId, documentId, body);
        });
    }

    private int connectionPid() {
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    private void awaitBlocked(int pid) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(scoped(() -> jdbc.queryForObject(
                    "SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid)))) return;
            Thread.sleep(20);
        }
        fail("concurrent transaction never waited for the document row");
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            assertTrue(release.await(5, TimeUnit.SECONDS), "transaction did not resume");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
