package io.supportflow.customer.service.bot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.supportflow.customer.dto.session.EscalateResult;
import io.supportflow.customer.dto.session.MessageVO;
import io.supportflow.customer.entity.Session;
import io.supportflow.customer.internal.BotAiClient;
import io.supportflow.customer.internal.RealtimeNotifyClient;
import io.supportflow.customer.service.session.SessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BotBrainDiagnosticsTest {
    private final BotAiClient ai = mock(BotAiClient.class);
    private final RealtimeNotifyClient notify = mock(RealtimeNotifyClient.class);
    private final SessionService sessions = mock(SessionService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(BotBrainService.class);
    private BotBrainService brain;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SessionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sessions);
        when(sessions.requireSession("TENANT_A", "S1")).thenReturn(
                Session.builder().id(1L).sessionNo("S1").status(SessionService.STATUS_BOT).build());
        when(sessions.historyByTenant(eq("TENANT_A"), eq("S1"), isNull(), anyInt(), eq(false))).thenReturn(List.of());
        brain = new BotBrainService(ai, notify, provider, true, true, 5);
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
        brain.shutdown();
    }

    @Test
    void imageAndReplyDiagnosticsKeepCountsAndPreserveDeliveredContent() {
        String order = "123456789012";
        String image = "PRIVATE_OCR_TEXT " + order;
        String reply = "PRIVATE_REPLY_TEXT " + order;
        when(ai.think(eq("TENANT_A"), eq("S1"), anyList(), eq(5))).thenReturn(Map.of(
                "reply", reply, "transfer_reason", "PRIVATE_TRANSFER_REASON", "engine", "PRIVATE_ENGINE",
                "intent", "PRIVATE_INTENT", "emotion", "PRIVATE_EMOTION"));
        var delivered = new MessageVO("2", "MSG2", null, 2L, 1L,
                SessionService.SENDER_BOT, null, 1, reply, null, null);
        when(sessions.appendMessage("TENANT_A", "S1", SessionService.SENDER_BOT, null, 1, reply)).thenReturn(delivered);

        brain.handleCustomerMessage("TENANT_A", "S1", image);

        verify(sessions).updateBotState("TENANT_A", 1L, "PRIVATE_INTENT", "PRIVATE_EMOTION");
        verify(sessions).appendMessage("TENANT_A", "S1", SessionService.SENDER_BOT, null, 1, reply);
        verify(notify).notifyMessage(eq("TENANT_A"), eq("S1"), argThat(data -> reply.equals(data.get("content"))), eq(true));
        String logs = formattedLogs();
        assertTrue(logs.contains("hitCount=1") && logs.contains("replyChars=") && logs.contains("reasonChars="));
        assertFalse(logs.contains("PRIVATE_") || logs.contains(order));
    }

    @Test
    void failureDiagnosticsRetainTypeWhileSelectingTheExistingHumanFallback() {
        when(ai.think(eq("TENANT_A"), eq("S1"), anyList(), eq(5)))
                .thenThrow(new IllegalStateException("PRIVATE_UPSTREAM_ERROR"));
        when(sessions.escalateToHuman(eq("TENANT_A"), eq("S1"), anyString(), anyString()))
                .thenReturn(new EscalateResult(null, 9L));

        brain.handleCustomerMessage("TENANT_A", "S1");

        verify(sessions).escalateToHuman(eq("TENANT_A"), eq("S1"), eq("智能客服暂时不可用，已转人工"), anyString());
        assertTrue(formattedLogs().contains("errorType=IllegalStateException"));
        assertFalse(formattedLogs().contains("PRIVATE_UPSTREAM_ERROR"));
        assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
    }

    private String formattedLogs() {
        return String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }
}
