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

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

/** A bounded, best-effort wake-up; the visitor message itself is already durable. */
@Component
@ConditionalOnProperty(name = "supportflow.bot.runtime-ticket-notice-push-enabled", havingValue = "true")
public final class AgentTicketNoticePublisher {
    private static final Logger log = LoggerFactory.getLogger(AgentTicketNoticePublisher.class);
    private static final int MAX_TARGETS = 4;
    private final List<RestClient> targets;

    public AgentTicketNoticePublisher(@Value("${supportflow.realtime.base-url:http://127.0.0.1:9096}") String base,
                                      @Value("${SUPPORTFLOW_CUSTOMER_REALTIME_SECRET:}") String secret,
                                      @Value("${supportflow.realtime.ticket-notice-base-urls:}") String configuredTargets) {
        if (!CustomerInternalCredential.usable(secret)) throw new IllegalArgumentException("Ticket notice signing key required");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(300);
        factory.setReadTimeout(1200);
        targets = origins(base, configuredTargets).stream().map(origin -> RestClient.builder().baseUrl(origin)
                .requestFactory(factory).requestInterceptor(CustomerInternalCredential.signer(secret,
                        CustomerInternalCredential.Caller.CUSTOMER_NOTICE)).build()).toList();
    }

    private static List<String> origins(String base, String configured) {
        if (configured.isBlank()) return List.of(base);
        String[] values = configured.split(",", -1);
        if (values.length > MAX_TARGETS) throw new IllegalArgumentException("At most four ticket notice origins are supported");
        var parsed = Arrays.stream(values).map(String::strip).map(AgentTicketNoticePublisher::origin).toList();
        if (parsed.stream().distinct().count() != parsed.size())
            throw new IllegalArgumentException("Ticket notice origins must be distinct");
        return parsed.stream().map(URI::toString).toList();
    }

    private static URI origin(String value) {
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid ticket notice origin"); }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Ticket notice targets must be HTTP(S) origins");
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("http".equals(uri.getScheme()) ? port == 80 : port == 443);
        return URI.create(uri.getScheme() + "://" + uri.getHost() + (defaultPort ? "" : ":" + port));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(AgentTicketNotice notice) {
        if (targets.size() == 1) {
            send(targets.getFirst(), notice);
            return;
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            targets.forEach(target -> executor.execute(() -> send(target, notice)));
        }
    }

    private void send(RestClient realtime, AgentTicketNotice notice) {
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
