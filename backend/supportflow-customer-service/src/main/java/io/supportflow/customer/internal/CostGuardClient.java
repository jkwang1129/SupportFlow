package io.supportflow.customer.internal;

import io.supportflow.customer.dto.cost.Guard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Checks the tenant's monthly cost policy before dispatching legacy AI requests. */
@Component
public class CostGuardClient {

    private static final Logger log = LoggerFactory.getLogger(CostGuardClient.class);
    private static final Pattern TENANT_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,15}");
    private static final long DECISION_TTL = Duration.ofSeconds(5).toNanos();
    private static final long FAILURE_TTL = Duration.ofSeconds(1).toNanos();
    private static final Guard UNAVAILABLE = new Guard(Guard.MODE_DEGRADE, "暂时无法核实 AI 预算");

    private final RestClient restClient;
    private final LongSupplier ticker;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    @Autowired
    public CostGuardClient(@Value("${supportflow.ops.internal-base-url:http://127.0.0.1:9095}") String baseUrl) {
        this(baseUrl, System::nanoTime);
    }

    CostGuardClient(String baseUrl, LongSupplier ticker) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(250));
        factory.setReadTimeout(Duration.ofMillis(1200));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.ticker = ticker;
    }

    public Guard check(String tenantCode) {
        String tenant = tenantCode == null ? "" : tenantCode.trim();
        if (!TENANT_CODE.matcher(tenant).matches() || "PLATFORM".equalsIgnoreCase(tenant)) {
            return UNAVAILABLE;
        }
        Entry entry = cache.computeIfAbsent(tenant, ignored -> new Entry());
        synchronized (entry) {
            Decision previous = entry.decision;
            if (previous != null && ticker.getAsLong() - previous.expiresAt() < 0) {
                return previous.guard();
            }
            Guard confirmed = fetch(tenant);
            if (confirmed != null) {
                entry.decision = new Decision(confirmed, ticker.getAsLong() + DECISION_TTL);
            } else {
                // Keep a confirmed pause in effect while Ops cannot verify a replacement policy.
                Guard fallback = previous != null && previous.guard().isBlocked() ? previous.guard() : UNAVAILABLE;
                entry.decision = new Decision(fallback, ticker.getAsLong() + FAILURE_TTL);
            }
            return entry.decision.guard();
        }
    }

    private Guard fetch(String tenant) {
        try {
            Map<?, ?> body = restClient.get()
                    .uri("/api/ops/internal/observability/cost-guard?tenantCode={tenant}", tenant)
                    .retrieve().body(Map.class);
            if (body != null && Integer.valueOf(0).equals(body.get("code"))
                    && body.get("data") instanceof Map<?, ?> data
                    && data.get("mode") instanceof Integer mode
                    && mode >= Guard.MODE_NORMAL && mode <= Guard.MODE_BLOCK
                    && data.get("reason") instanceof String reason) {
                return new Guard(mode, reason);
            }
            log.debug("成本闸门响应格式无效 tenant={}", tenant);
        } catch (RestClientException e) {
            log.debug("成本闸门查询失败 tenant={} errorType={}", tenant, e.getClass().getSimpleName());
        }
        return null;
    }

    private static final class Entry {
        private Decision decision;
    }

    private record Decision(Guard guard, long expiresAt) { }
}
