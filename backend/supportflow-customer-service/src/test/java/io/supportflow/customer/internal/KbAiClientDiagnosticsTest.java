package io.supportflow.customer.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.dto.cost.Guard;
import io.supportflow.customer.service.metrics.BillingUsageCollector;
import io.supportflow.customer.testkit.RuntimeReceiverStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KbAiClientDiagnosticsTest {
    private static final String TENANT = "TENANT_A";
    private static final String INDEX_RESULT = """
            {"docId":7,"fileName":"PRIVATE_FILENAME.txt","chunkCount":1,
             "preview":[{"content":"PRIVATE_INDEX_PREVIEW"}]}
            """;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(KbAiClient.class);
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    @Test
    void indexingSearchAndAnswersPreserveResultsWithoutWritingContentToLogs() throws Exception {
        try (var receiver = new RuntimeReceiverStub(request -> {
            String body = new String(request.body(), StandardCharsets.UTF_8);
            return switch (request.rawPath()) {
                case "/api/ai/v1/kb/index" -> {
                    assertEquals("PRIVATE_FILE_CONTENT", body);
                    yield new RuntimeReceiverStub.Reply(200, INDEX_RESULT);
                }
                case "/api/ai/v1/kb/index-text" -> {
                    assertTrue(body.contains("PRIVATE_INDEX_TEXT"));
                    yield new RuntimeReceiverStub.Reply(200, INDEX_RESULT);
                }
                case "/api/ai/v1/kb/search" -> {
                    assertTrue(body.contains("PRIVATE_SEARCH_QUERY"));
                    yield new RuntimeReceiverStub.Reply(200, """
                            {"results":[{"content":"PRIVATE_SEARCH_RESULT"}]}
                            """);
                }
                case "/api/ai/v1/rag/ask" -> {
                    assertTrue(body.contains("PRIVATE_QUESTION"));
                    yield new RuntimeReceiverStub.Reply(200, """
                            {"answer":"PRIVATE_ANSWER","citations":[]}
                            """);
                }
                default -> throw new AssertionError("Unexpected KB route");
            };
        })) {
            var client = client(receiver);
            var file = client.indexFile(TENANT, 7, "PRIVATE_FILENAME.txt", "PRIVATE_FILE_CONTENT".getBytes(StandardCharsets.UTF_8));
            assertEquals("PRIVATE_FILENAME.txt", file.fileName());
            assertEquals("PRIVATE_INDEX_PREVIEW", file.preview().getFirst().get("content"));
            assertEquals(1, client.indexText(TENANT, 7, "PRIVATE_INDEX_TEXT").chunkCount());
            assertEquals("PRIVATE_SEARCH_RESULT", client.search(TENANT, "PRIVATE_SEARCH_QUERY", 5).getFirst().get("content"));
            assertEquals("PRIVATE_ANSWER", client.ask(TENANT, "PRIVATE_QUESTION", 5).get("answer"));
            assertEquals(4, receiver.calls.get());
            assertNull(receiver.failure.get());
        }

        String logs = formattedLogs();
        assertTrue(logs.contains("operation=index-file status=200 responseChars="));
        assertTrue(logs.contains("operation=search status=200 responseChars="));
        assertTrue(logs.contains("operation=ask status=200 responseChars="));
        assertFalse(logs.contains("PRIVATE_"));
    }

    @Test
    void upstreamFailuresRetainCodesWithoutCarryingTheResponseBody() throws Exception {
        try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(503, """
                {"detail":"PRIVATE_UPSTREAM_ERROR"}
                """))) {
            var client = client(receiver);
            BizException indexFailure = assertThrows(BizException.class, () -> client.indexText(TENANT, 7, "PRIVATE_TEXT"));
            assertEquals(40001, indexFailure.getCode());
            assertEquals("知识服务返回 HTTP 503", indexFailure.getMessage());
            BizException searchFailure = assertThrows(BizException.class, () -> client.search(TENANT, "PRIVATE_QUERY", 5));
            assertEquals(50001, searchFailure.getCode());
            assertEquals("知识服务返回 HTTP 503", searchFailure.getMessage());
        }
        try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(200, "PRIVATE_INVALID_JSON"))) {
            BizException failure = assertThrows(BizException.class, () -> client(receiver).ask(TENANT, "PRIVATE_QUESTION", 5));
            assertEquals("知识问答失败", failure.getMessage());
        }

        String logs = formattedLogs();
        assertTrue(logs.contains("operation=index-text status=503"));
        assertTrue(logs.contains("errorType=JsonParseException"));
        assertFalse(logs.contains("PRIVATE_"));
        assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
    }

    private KbAiClient client(RuntimeReceiverStub receiver) {
        CostGuardClient guard = mock(CostGuardClient.class);
        when(guard.check(TENANT)).thenReturn(Guard.NORMAL);
        return new KbAiClient("test-only-kb-signing-key-1234567890", receiver.endpoint().resolve("/").toString(),
                true, mock(BillingUsageCollector.class), guard);
    }

    private String formattedLogs() {
        return String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }
}
