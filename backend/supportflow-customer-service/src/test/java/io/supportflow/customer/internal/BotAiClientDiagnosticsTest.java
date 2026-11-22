package io.supportflow.customer.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.supportflow.common.context.TraceContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.dto.cost.Guard;
import io.supportflow.customer.service.metrics.BillingUsageCollector;
import io.supportflow.customer.testkit.RuntimeReceiverStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotAiClientDiagnosticsTest {
    private static final String TENANT = "TENANT_A";
    private static final String QUESTION = "PRIVATE_CUSTOMER_QUESTION";
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(BotAiClient.class);
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
    void sendsConversationAndReturnsReplyWhileLoggingOnlyMetadata() throws Exception {
        String traceId = TraceContext.getOrCreate();
        try (var receiver = new RuntimeReceiverStub(request -> {
            assertEquals("/api/ai/v1/agent/brain", request.rawPath());
            assertTrue(request.headers().entrySet().stream().anyMatch(header ->
                    header.getKey().equalsIgnoreCase(TraceContext.HEADER_REQUEST_ID)
                            && header.getValue().equals(List.of(traceId))));
            assertTrue(new String(request.body(), StandardCharsets.UTF_8).contains(QUESTION));
            return new RuntimeReceiverStub.Reply(200, """
                    {"reply":"PRIVATE_MODEL_REPLY"}
                    """);
        })) {
            assertEquals("PRIVATE_MODEL_REPLY", TraceContext.bind(traceId,
                    () -> client(receiver).think(TENANT, "S1", messages(), 5).get("reply")));
            assertEquals(1, receiver.calls.get());
            assertNull(receiver.failure.get());
        }

        String logs = formattedLogs();
        assertTrue(logs.contains("traceId=" + traceId));
        assertTrue(logs.contains("historyCount=1"));
        assertTrue(logs.contains("status=200 responseChars="));
        assertFalse(logs.contains("PRIVATE_"));
    }

    @Test
    void httpAndJsonErrorsDoNotExposeUpstreamBodies() throws Exception {
        try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(
                503, """
                {"detail":"PRIVATE_UPSTREAM_ERROR"}
                """))) {
            BizException failure = assertThrows(BizException.class, () -> client(receiver).think(TENANT, "S1", messages(), 5));
            assertEquals("客服大脑返回 HTTP 503", failure.getMessage());
        }
        try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(200, "PRIVATE_INVALID_JSON"))) {
            BizException failure = assertThrows(BizException.class, () -> client(receiver).think(TENANT, "S1", messages(), 5));
            assertEquals("客服大脑调用失败", failure.getMessage());
        }

        String logs = formattedLogs();
        assertTrue(logs.contains("status=503 responseChars="));
        assertTrue(logs.contains("errorType=JsonParseException"));
        assertFalse(logs.contains("PRIVATE_"));
        assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
    }

    private BotAiClient client(RuntimeReceiverStub receiver) {
        CostGuardClient guard = mock(CostGuardClient.class);
        when(guard.check(TENANT)).thenReturn(Guard.NORMAL);
        return new BotAiClient("test-only-bot-signing-key-123456789", receiver.endpoint().resolve("/").toString(),
                true, 5, mock(BillingUsageCollector.class), guard);
    }

    private List<Map<String, String>> messages() {
        return List.of(BotAiClient.message("user", QUESTION));
    }

    private String formattedLogs() {
        return String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }
}
