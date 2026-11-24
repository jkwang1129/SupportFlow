package io.supportflow.customer.internal;

import io.supportflow.common.auth.CustomerInternalCredential;
import io.supportflow.common.context.TenantContext;
import io.supportflow.customer.event.AgentTicketNotice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;

/** A bounded, best-effort wake-up; the visitor message itself is already durable. */
@Component
@ConditionalOnProperty(name = "supportflow.bot.runtime-ticket-notice-push-enabled", havingValue = "true")
public final class AgentTicketNoticePublisher {
    private static final Logger log = LoggerFactory.getLogger(AgentTicketNoticePublisher.class);
    private final RestClient realtime;

    public AgentTicketNoticePublisher(@Value("${supportflow.realtime.base-url:http://127.0.0.1:9096}") String base,
                                      @Value("${SUPPORTFLOW_CUSTOMER_REALTIME_SECRET:}") String secret) {
        if (!CustomerInternalCredential.usable(secret)) throw new IllegalArgumentException("Ticket notice signing key required");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(300);
        factory.setReadTimeout(1200);
        realtime = RestClient.builder().baseUrl(base).requestFactory(factory)
                .requestInterceptor(CustomerInternalCredential.signer(secret, CustomerInternalCredential.Caller.CUSTOMER_NOTICE))
                .build();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(AgentTicketNotice notice) {
        try {
            realtime.post()
                    .uri("/api/realtime/internal/ticket-notices/{sessionNo}/{sequence}/{messageId}",
                            notice.sessionNo(), notice.sequence(), notice.messageId())
                    .header(TenantContext.HEADER_TENANT_CODE, notice.tenant())
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException failure) {
            log.warn("Ticket notice push unavailable tenant={} sessionNo={} seq={} errorType={}",
                    notice.tenant(), notice.sessionNo(), notice.sequence(), failure.getClass().getSimpleName());
        }
    }
}
