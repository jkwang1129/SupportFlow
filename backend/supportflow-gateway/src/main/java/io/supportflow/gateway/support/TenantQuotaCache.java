package io.supportflow.gateway.support;

import io.supportflow.gateway.config.TenantGuardProperties;
import io.supportflow.gateway.security.JwtTenantParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户配额缓存（网关侧）。
 *
 * <p>为什么要缓存：网关是每个请求都要过的入口，不能每来一个请求就问一次 tenant-service。
 * 缓 30 秒的代价是"平台改了 QPS，最迟 30 秒生效"，对限流场景完全够用；
 * 也让 tenant-service 挂了之后网关还能按缓存（或兜底默认值）继续工作。</p>
 */
@Component
public class TenantQuotaCache {

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaCache.class);

    private final TenantGuardProperties properties;
    private final WebClient webClient;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public TenantQuotaCache(TenantGuardProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.builder()
                .baseUrl(properties.getTenantServiceUrl())
                .build();
    }

    /** 取配额：命中缓存直接返回；否则同步拉一次（拉不到用兜底默认值） */
    public Quota get(String tenantCode) {
        Entry entry = cache.get(tenantCode);
        long now = System.currentTimeMillis();
        if (entry != null && entry.expireAt > now) {
            return entry.quota;
        }
        Quota quota = fetch(tenantCode);
        cache.put(tenantCode, new Entry(quota, now + properties.getQuotaCacheSeconds() * 1000L));
        return quota;
    }

    /** 配额变更后主动失效（留给后续接入配置变更通知） */
    public void invalidate(String tenantCode) {
        cache.remove(tenantCode);
    }

    private Quota fetch(String tenantCode) {
        try {
            // 用 String 接响应（不引 JSON 库、也不依赖网关的 JSON 解码器配置）
            String body = webClient.get()
                    .uri("/api/tenant/internal/isolation/quota/{tenantCode}", tenantCode)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(2));
            String data = extractData(body);
            if (data == null || !data.contains("\"exists\":true")) {
                // 查不到租户：给一个很保守的额度，避免被刷
                return Quota.unknown(properties.getDefaultQps());
            }
            return new Quota(
                    true,
                    (int) JwtTenantParser.longClaim(data, "tenantStatus"),
                    !data.contains("\"enabled\":false"),
                    (int) orDefault(JwtTenantParser.longClaim(data, "qpsLimit"), properties.getDefaultQps()),
                    JwtTenantParser.longClaim(data, "dailyRequestLimit"),
                    JwtTenantParser.longClaim(data, "dailySessionLimit"),
                    JwtTenantParser.longClaim(data, "aiDailyLimit"));
        } catch (Exception e) {
            log.debug("拉取租户配额失败 tenant={} error={}（用兜底默认值）", tenantCode, e.getMessage());
            return new Quota(true, 1, true, properties.getDefaultQps(), 0, 0, 0);
        }
    }

    /** 从 `{"code":0,"data":{…}}` 里抠出 data 那一段（够用且不引 JSON 库） */
    private static String extractData(String body) {
        if (body == null) {
            return null;
        }
        int start = body.indexOf("\"data\"");
        if (start < 0) {
            return null;
        }
        int brace = body.indexOf('{', start);
        if (brace < 0) {
            return null;
        }
        int depth = 0;
        for (int i = brace; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return body.substring(brace, i + 1);
                }
            }
        }
        return null;
    }

    private static long orDefault(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    private record Entry(Quota quota, long expireAt) {
    }

    /** 租户配额快照 */
    public record Quota(boolean exists, int tenantStatus, boolean enabled, int qpsLimit,
                        long dailyRequestLimit, long dailySessionLimit, long aiDailyLimit) {

        static Quota unknown(int defaultQps) {
            return new Quota(false, 1, true, defaultQps, 0, 0, 0);
        }

        /** 冻结的租户（status=2）直接拒绝，不是限流 */
        public boolean frozen() {
            return tenantStatus == 2;
        }
    }
}
