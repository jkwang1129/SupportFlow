package io.supportflow.customer.security;

import io.supportflow.common.context.TenantContext;
import io.supportflow.customer.controller.session.SessionAttachmentController;
import io.supportflow.customer.controller.session.SessionController;
import io.supportflow.customer.dto.session.OpenBody;
import io.supportflow.customer.service.session.SessionAttachmentService;
import io.supportflow.customer.service.session.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VisitorTenantEntryTest {
    @Test
    void resolvesChannelBeforeStartingSessionAndUploadTransactions() {
        var resolver = mock(ChannelTenantResolver.class);
        var sessions = mock(SessionService.class);
        var attachments = mock(SessionAttachmentService.class);
        when(resolver.resolve("channel-key")).thenReturn("TENANT_A");
        when(sessions.openSession(any())).thenAnswer(call -> {
            assertEquals("TENANT_A", TenantContext.get());
            return null;
        });
        when(attachments.upload(eq("TENANT_A"), eq("S1"), isNull())).thenAnswer(call -> {
            assertEquals("TENANT_A", TenantContext.get());
            return null;
        });
        var controller = new SessionController(sessions, mock(OpenSessionRateLimiter.class), null, resolver);
        controller.open(new OpenBody("channel-key", null, "Visitor"), new MockHttpServletRequest());
        var access = mock(ChatAttachmentAccess.class);
        when(access.tenant("S1", "visitor")).thenReturn("TENANT_A");
        new SessionAttachmentController(attachments, resolver, access).upload("channel-key", "S1", "visitor", null);
        assertNull(TenantContext.get());
        verify(sessions).openSession(any());
        verify(attachments).upload("TENANT_A", "S1", null);
    }
}
