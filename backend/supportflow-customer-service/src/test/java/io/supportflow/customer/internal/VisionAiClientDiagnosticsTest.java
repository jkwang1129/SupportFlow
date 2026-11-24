package io.supportflow.customer.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.supportflow.common.auth.AiRequestSigner;
import io.supportflow.common.context.TraceContext;
import io.supportflow.customer.dto.cost.Guard;
import io.supportflow.customer.service.bot.VisionService;
import io.supportflow.customer.service.metrics.BillingUsageCollector;
import io.supportflow.customer.service.session.SessionAttachmentService;
import io.supportflow.customer.testkit.RuntimeReceiverStub;
import io.supportflow.customer.testkit.RuntimeReceiverStub.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VisionAiClientDiagnosticsTest {
    private static final String TENANT = "TENANT_A";
    private static final String SESSION = "session-fixture";
    private static final String QUESTION = "fixture-private-question";
    private static final String IMAGE = "data:image/jpeg;base64,Zml4dHVyZS1pbWFnZQ==";
    private static final String OCR = "fixture-private-ocr";
    private static final String ORDER = "fixture-private-order";
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final List<Logger> loggers = List.of(
            (Logger) LoggerFactory.getLogger(VisionAiClient.class),
            (Logger) LoggerFactory.getLogger(VisionService.class));
    private final List<Level> levels = new ArrayList<>();
    private final BillingUsageCollector billing = mock(BillingUsageCollector.class);

    @BeforeEach
    void attach() {
        appender.start();
        for (Logger logger : loggers) {
            levels.add(logger.getLevel());
            logger.setLevel(Level.INFO);
            logger.addAppender(appender);
        }
    }

    @AfterEach
    void detach() {
        for (int index = 0; index < loggers.size(); index++) {
            Logger logger = loggers.get(index);
            logger.detachAppender(appender);
            logger.setLevel(levels.get(index));
        }
        appender.stop();
    }

    @Test
    void signedVisionCallKeepsOcrAndOrderInBusinessResultOnly() throws Exception {
        try (var receiver = new RuntimeReceiverStub(request -> {
            assertEquals("POST", request.method());
            assertEquals("/api/ai/v1/agent/vision", request.rawPath());
            assertTrue(request.headers().keySet().stream().anyMatch(AiRequestSigner.HEADER::equalsIgnoreCase));
            assertTrue(request.headers().entrySet().stream().anyMatch(header ->
                    header.getKey().equalsIgnoreCase(TraceContext.HEADER_REQUEST_ID)
                            && header.getValue().equals(List.of("fixture-trace"))));
            String json = new String(request.body(), StandardCharsets.UTF_8);
            assertTrue(json.contains(QUESTION) && json.contains(IMAGE));
            return new Reply(200, """
                    {"available":true,"ocr_text":"%s","order_no":"%s","summary":"fixture-private-summary"}
                    """.formatted(OCR, ORDER));
        })) {
            var vision = service(receiver);
            var result = TraceContext.bind("fixture-trace",
                    () -> vision.recognize(TENANT, SESSION, List.of(1L), QUESTION));
            assertTrue(result.available());
            assertEquals(OCR, result.ocrText());
            assertEquals(ORDER, result.orderNo());
            assertTrue(vision.toBrainQuestion(result, QUESTION).contains(OCR));
            assertEquals(1, receiver.calls.get());
            assertNull(receiver.failure.get());
            verify(billing).countAiCall(TENANT);
        }
        assertTrue(logText().contains("traceId=fixture-trace status=200"));
        assertTrue(logText().contains("ocrChars="));
        assertFalse(logText().contains("fixture-private-"));
        assertFalse(logText().contains(IMAGE));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 503})
    void malformedBodyAndServiceErrorDoNotEscapeToLogsOrBusinessHint(int status) throws Exception {
        try (var receiver = new RuntimeReceiverStub(request -> new Reply(status, "fixture-private-error"))) {
            var result = service(receiver).recognize(TENANT, SESSION, List.of(1L), QUESTION);
            assertFalse(result.available());
            assertEquals(status == 503 ? "图片识别服务返回 HTTP 503" : "图片识别调用失败", result.hint());
            assertEquals(1, receiver.calls.get());
        }
        assertTrue(logText().contains("status=" + status));
        assertFalse(logText().contains("fixture-private-"));
        assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
    }

    private VisionService service(RuntimeReceiverStub receiver) {
        var guard = mock(CostGuardClient.class);
        when(guard.check(TENANT)).thenReturn(Guard.NORMAL);
        var attachment = mock(SessionAttachmentService.class);
        when(attachment.toVisionImages(TENANT, SESSION, List.of(1L))).thenReturn(List.of(IMAGE));
        var client = new VisionAiClient("test-only-vision-signing-key-123456789",
                receiver.endpoint().resolve("/").toString(), true, 5, billing, guard);
        return new VisionService(attachment, client);
    }

    private String logText() {
        return String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }
}
