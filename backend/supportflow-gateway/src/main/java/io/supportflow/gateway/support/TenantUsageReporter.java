package io.supportflow.gateway.support;

import io.supportflow.gateway.config.TenantGuardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用量与告警上报（网关 → tenant-service）。
 *
 * <p>为什么批量上报而不是每请求写库：限流计数是"高频、可丢一点"的数据，
 * 每请求写库会把库打死。这里在内存里计数，每 5 秒打包上报一次；
 * 网关重启最多丢 5 秒计数——对用量展示与计费口径可以接受。</p>
 */
@Component
public class TenantUsageReporter {

    private static final Logger log = LoggerFactory.getLogger(TenantUsageReporter.class);

    private final WebClient webClient;

    private final Map<String, Counters> counters = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> pendingEvents = new ArrayList<>();

    public TenantUsageReporter(TenantGuardProperties properties) {
        this.webClient = WebClient.builder()
                .baseUrl(properties.getTenantServiceUrl())
                .build();
    }

    public void countRequest(String tenantCode) {
        countersOf(tenantCode).requests.incrementAndGet();
    }

    public void countRejected(String tenantCode) {
        countersOf(tenantCode).rejected.incrementAndGet();
    }

    /** 告警（篡改租户头 / 跨租户访问）即时入队，随用量一起上报 */
    public void reportEvent(String tenantCode, int eventType, String detail,
                            String uri, String method, String clientIp, String tokenTenant) {
        Map<String, Object> event = new HashMap<>();
        event.put("tenantCode", tenantCode == null || tenantCode.isBlank() ? "PLATFORM" : tenantCode);
        event.put("eventType", eventType);
        event.put("detail", detail);
        event.put("requestUri", uri);
        event.put("requestMethod", method);
        event.put("clientIp", clientIp);
        event.put("tokenTenant", tokenTenant);
        synchronized (pendingEvents) {
            if (pendingEvents.size() < 200) {
                pendingEvents.add(event);
            }
        }
    }

    /** 每 5 秒：把内存里的用量与告警打包上报（失败只记日志，不影响转发） */
    @Scheduled(fixedDelayString = "${supportflow.gateway.tenant-guard.usage-report-seconds:5}000")
    public void flush() {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> usage = new ArrayList<>();
        for (Map.Entry<String, Counters> entry : new HashMap<>(counters).entrySet()) {
            Counters value = entry.getValue();
            long requests = value.requests.getAndSet(0);
            long rejected = value.rejected.getAndSet(0);
            if (requests == 0 && rejected == 0) {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("tenantCode", entry.getKey());
            row.put("statDate", today.toString());
            row.put("requestCount", requests);
            row.put("rejectedCount", rejected);
            row.put("sessionCount", 0);
            row.put("aiCallCount", 0);
            usage.add(row);
        }
        List<Map<String, Object>> events;
        synchronized (pendingEvents) {
            events = new ArrayList<>(pendingEvents);
            pendingEvents.clear();
        }
        if (!usage.isEmpty()) {
            post("/api/tenant/internal/isolation/usage/report", usage);
        }
        for (Map<String, Object> event : events) {
            post("/api/tenant/internal/isolation/security-event", event);
        }
    }

    private void post(String uri, Object body) {
        try {
            webClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block(Duration.ofSeconds(2));
        } catch (Exception e) {
            log.debug("用量/告警上报失败 uri={} error={}", uri, e.getMessage());
        }
    }

    private Counters countersOf(String tenantCode) {
        String key = tenantCode == null || tenantCode.isBlank() ? "PLATFORM" : tenantCode;
        return counters.computeIfAbsent(key, ignored -> new Counters());
    }

    private static final class Counters {
        private final AtomicLong requests = new AtomicLong();
        private final AtomicLong rejected = new AtomicLong();
    }
}
