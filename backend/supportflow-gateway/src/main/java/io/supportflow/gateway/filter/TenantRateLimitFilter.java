package io.supportflow.gateway.filter;

import io.supportflow.common.context.TenantContext;
import io.supportflow.gateway.config.TenantGuardProperties;
import io.supportflow.gateway.security.JwtTenantParser;
import io.supportflow.gateway.support.RequestIps;
import io.supportflow.gateway.support.TenantQuotaCache;
import io.supportflow.gateway.support.TenantUsageReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户级限流（网关是执行点）。
 *
 * <p>为什么按租户限流：一套 SaaS 共用一套网关，A 企业跑批量任务把 QPS 打满，
 * B 企业就跟着卡——这就是"吵起来"。按 tenant_code 各自一个令牌桶 + 日额度，
 * 一个租户刷爆只会 429 它自己。</p>
 *
 * <p>两层限制：
 * 1. **QPS**：令牌桶（容量 = QPS，按纳秒补充），防瞬时刷；
 * 2. **日额度**：每天请求总数（tenant_quota.daily_request_limit），防"细水长流"式滥用。</p>
 *
 * <p>免限流前缀见 TenantGuardProperties.skipPrefixes：认证接口、内部接口、
 * 平台隔离管理接口都要放过——被自己的限流挡住登录或排查入口，是最典型的自伤。</p>
 */
@Component
public class TenantRateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TenantRateLimitFilter.class);

    private final TenantGuardProperties properties;
    private final JwtTenantParser jwtTenantParser;
    private final TenantQuotaCache quotaCache;
    private final TenantUsageReporter reporter;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, DailyCounter> dailyCounters = new ConcurrentHashMap<>();

    public TenantRateLimitFilter(TenantGuardProperties properties,
                                 JwtTenantParser jwtTenantParser,
                                 TenantQuotaCache quotaCache,
                                 TenantUsageReporter reporter) {
        this.properties = properties;
        this.jwtTenantParser = jwtTenantParser;
        this.quotaCache = quotaCache;
        this.reporter = reporter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isRateLimitEnabled()) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        if (skip(path)) {
            return chain.filter(exchange);
        }
        String tenant = tenantOf(request);
        if (tenant == null || tenant.isBlank()) {
            // 认不出租户（未认证且没带租户头）：交给下游按未认证处理，不在这里限流
            return chain.filter(exchange);
        }

        TenantQuotaCache.Quota quota = quotaCache.get(tenant);
        if (!quota.enabled()) {
            reporter.countRequest(tenant);
            return chain.filter(exchange);
        }
        long now = System.nanoTime();
        Bucket bucket = buckets.computeIfAbsent(tenant, key -> new Bucket(quota.qpsLimit()));
        int limit = quota.qpsLimit();
        if (limit <= 0) {
            // 0 = 不限 QPS，但要记用量
            reporter.countRequest(tenant);
            return chain.filter(exchange);
        }
        if (!bucket.tryAcquire(limit, now)) {
            reporter.countRejected(tenant);
            reporter.reportEvent(tenant, 3,
                    "触发租户 QPS 限流：" + limit + "/秒", path, request.getMethod().name(),
                    RequestIps.clientIp(exchange), tenant);
            log.warn("租户 {} 触发 QPS 限流 path={} limit={}", tenant, path, limit);
            return tooMany(exchange, limit, 0, "请求过于频繁：当前企业 QPS 上限 " + limit + "，请稍后重试");
        }
        DailyCounter counter = dailyCounter(tenant);
        long todayCount = counter.increment();
        long dailyLimit = quota.dailyRequestLimit();
        if (dailyLimit > 0 && todayCount > dailyLimit) {
            reporter.countRejected(tenant);
            reporter.reportEvent(tenant, 3,
                    "触发租户日额度限流：" + todayCount + "/" + dailyLimit, path,
                    request.getMethod().name(), RequestIps.clientIp(exchange), tenant);
            log.warn("租户 {} 触发达日额度限流 path={} count={} limit={}",
                    tenant, path, todayCount, dailyLimit);
            return tooMany(exchange, limit, 0,
                    "今日请求量已达套餐上限（" + dailyLimit + " 次），请升级套餐或次日再试");
        }
        reporter.countRequest(tenant);
        return chain.filter(exchange);
    }

    private boolean skip(String path) {
        for (String prefix : properties.getSkipPrefixes()) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String tenantOf(ServerHttpRequest request) {
        JwtTenantParser.ParsedToken token =
                jwtTenantParser.parse(bearer(request.getHeaders().getFirst("Authorization")));
        if (token != null && token.tenantCode() != null) {
            return token.tenantCode();
        }
        return request.getHeaders().getFirst(TenantContext.HEADER_TENANT_CODE);
    }

    private static String bearer(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String value = authorization.trim();
        return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : value;
    }

    private DailyCounter dailyCounter(String tenant) {
        LocalDate today = LocalDate.now();
        DailyCounter counter = dailyCounters.get(tenant);
        if (counter == null || !today.equals(counter.date)) {
            counter = new DailyCounter(today);
            dailyCounters.put(tenant, counter);
        }
        return counter;
    }

    private static Mono<Void> tooMany(ServerWebExchange exchange, int limit, long remaining, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().set("X-RateLimit-Limit", String.valueOf(limit));
        response.getHeaders().set("X-RateLimit-Remaining", String.valueOf(Math.max(0, remaining)));
        response.getHeaders().set("Retry-After", "1");
        return TenantGuardFilter.reject(exchange, HttpStatus.TOO_MANY_REQUESTS, 42901, message);
    }

    @Override
    public int getOrder() {
        // 在"认租户"之后执行（TenantGuardFilter 是 -100）
        return -90;
    }

    // 令牌桶：容量 = QPS，按时间线性补充。实现刻意简单（单机内存），
    // 多实例部署时每个实例各自限流（要精确全局限流就换成 Redis 令牌桶，见文档 §5.2）。
    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        Bucket(int capacity) {
            this.tokens = Math.max(1, capacity);
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryAcquire(int qps, long nowNanos) {
            double capacity = Math.max(1, qps);
            double elapsedSeconds = (nowNanos - lastRefillNanos) / 1_000_000_000.0;
            lastRefillNanos = nowNanos;
            tokens = Math.min(capacity, tokens + elapsedSeconds * qps);
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }

    // 日计数：只做"当天累计"，跨天自动重置（精确的多实例统计交给 tenant-service 的累加表）
    private static final class DailyCounter {
        private final LocalDate date;
        private long count;

        DailyCounter(LocalDate date) {
            this.date = date;
        }

        synchronized long increment() {
            count += 1;
            return count;
        }
    }
}
