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
import java.util.List;
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
            jdbc.update("DELETE FROM kb_chunk WHERE doc_id=?", documentId);
            return jdbc.update("DELETE FROM kb_document WHERE id=?", documentId);
        });
    }

    @Test
    void reindexReadsCommittedBodyAfterConcurrentEdit() throws Exception {
        var updated = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reindexPid = new CompletableFuture<Integer>();
        when(ai.indexText(eq("TENANT_A"), eq(documentId), anyString())).thenAnswer(call -> {
            String body = call.getArgument(2);
            if (updated.getCount() != 0) {
                updated.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS), "edit did not resume");
            }
            // The indexer commits to the chunk table on a separate connection.
            CompletableFuture.runAsync(() -> replaceChunk(body)).get(5, TimeUnit.SECONDS);
            return new IndexResult(Long.toString(documentId), "", body.length(), 1, 0, "fixture", "fixture", List.of());
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
        verify(ai, times(2)).indexText("TENANT_A", documentId, NEW_POLICY);
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
    void deletingDocumentCommitsBothChangesWithoutWaitingForAnAiRequest() {
        tx(() -> {
            knowledge.delete(EDITOR, documentId);
            return null;
        });
        assertTrue(scoped(() -> jdbc.queryForObject(
                "SELECT is_deleted FROM kb_document WHERE id=?", Boolean.class, documentId)));
        assertEquals(0, scoped(() -> jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunk WHERE doc_id=?", Integer.class, documentId)));
        verifyNoInteractions(ai);
    }

    private <T> T tx(Supplier<T> action) {
        return scoped(() -> transaction.execute(status -> action.get()));
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
