package io.supportflow.realtime.controller;

import io.supportflow.common.auth.CustomerInternalCredential;
import io.supportflow.common.context.TenantContext;
import io.supportflow.realtime.client.SessionApiClient;
import io.supportflow.realtime.ws.RealtimeWebSocketHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Collections;

/** Accept only a signed reference, never the caller's proposed chat message. */
@RestController
public final class TicketNoticeController {
    private final String secret;
    private final SessionApiClient sessions;
    private final RealtimeWebSocketHandler realtime;

    public TicketNoticeController(@Value("${SUPPORTFLOW_CUSTOMER_REALTIME_SECRET:}") String secret,
                                  SessionApiClient sessions, RealtimeWebSocketHandler realtime) {
        this.secret = secret;
        this.sessions = sessions;
        this.realtime = realtime;
    }

    @PostMapping("/api/realtime/internal/ticket-notices/{sessionNo}/{sequence}/{messageId}")
    public ResponseEntity<Void> publish(HttpServletRequest request, @PathVariable String sessionNo,
                                        @PathVariable long sequence, @PathVariable String messageId) {
        var tenants = Collections.list(request.getHeaders(TenantContext.HEADER_TENANT_CODE));
        var signatures = Collections.list(request.getHeaders(CustomerInternalCredential.HEADER));
        if (tenants.size() != 1 || signatures.size() != 1
                || !CustomerInternalCredential.verify(signatures.getFirst(), secret,
                        CustomerInternalCredential.Caller.CUSTOMER_NOTICE, request.getMethod(),
                        request.getRequestURI(), request.getQueryString(), tenants.getFirst(), Clock.systemUTC())) {
            return ResponseEntity.status(401).build();
        }
        String tenant = tenants.getFirst();
        if (!tenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}") || "PLATFORM".equalsIgnoreCase(tenant)
                || request.getQueryString() != null || sequence < 1 || !messageId.matches("[1-9][0-9]{0,18}")
                || !sessionNo.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")) {
            return ResponseEntity.badRequest().build();
        }
        var rows = sessions.visitorTicketNotice(tenant, sessionNo, sequence);
        if (rows.size() != 1) return ResponseEntity.notFound().build();
        var message = rows.getFirst();
        if (!messageId.equals(message.msgId()) || !Long.valueOf(sequence).equals(message.seq())
                || !Integer.valueOf(4).equals(message.senderType()) || !Integer.valueOf(5).equals(message.msgType())
                || !Integer.valueOf(1).equals(message.visibleTo()) || message.senderId() != null
                || message.clientMsgNo() == null || !message.clientMsgNo().matches(
                        "agent-ticket-notice:[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")
                || message.content() == null || !message.content().matches("工单已创建，编号：[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            return ResponseEntity.notFound().build();
        }
        realtime.notifyTicketNotice(tenant, sessionNo, message);
        return ResponseEntity.noContent().build();
    }
}
