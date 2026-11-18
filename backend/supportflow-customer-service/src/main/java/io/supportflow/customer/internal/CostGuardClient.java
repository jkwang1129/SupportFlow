package io.supportflow.customer.internal;

import io.supportflow.customer.dto.cost.Guard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 成本闸门：调模型之前问一次"这个租户还能不能花"。
 *
 * <p><b>这是「成本预算 + 超限动作」的执行点。</b>没有它，平台配的
 * 「超限降级 / 暂停」就只是配置项——账单会照涨。四个调模型的入口
 * （客服大脑 / 知识问答 / 图片识别 / 质检）在真正发请求前都过一遍这里。</p>
 *
 * <p>三条约定：</p>
 * <ol>
 *   <li><b>拿不到答案就放行</b>：ops 没起来不该变成"全站 AI 不能用"，
 *       成本治理是经营动作，不是可用性开关；</li>
 *   <li><b>结果缓存 60 秒</b>：每个请求都问一次 ops 太贵，而这个粒度的偏差可以接受；</li>
 *   <li><b>降级与暂停分开</b>：降级 = 跳过模型但仍把话接住（不转人工）；
 *       暂停 = 直接走人工，客户不会没人管。</li>
 * </ol>
 */
@Component
public class CostGuardClient {

    private static final Logger log = LoggerFactory.getLogger(CostGuardClient.class);

    /** 与 ops 侧缓存对齐：60 秒 */
    private static final long CACHE_MILLIS = 60_000L;

    private final RestClient restClient;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public CostGuardClient(@Value("${supportflow.ops.internal-base-url:http://127.0.0.1:9095}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** 查一次闸门（带缓存）；任何异常都按"正常"返回 */
    @SuppressWarnings("unchecked")
    public Guard check(String tenantCode) {
        String tenant = tenantCode == null ? "" : tenantCode.trim();
        if (tenant.isEmpty()) {
            return Guard.NORMAL;
        }
        Entry cached = cache.get(tenant);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < CACHE_MILLIS) {
            return cached.guard();
        }
        Guard guard = Guard.NORMAL;
        try {
            Map<String, Object> body = restClient.get()
                    .uri(URI.create("/api/ops/internal/observability/cost-guard?tenantCode=" + tenant))
                    .retrieve().body(Map.class);
            Object data = body == null ? null : body.get("data");
            if (data instanceof Map<?, ?> map) {
                int mode = intOf(map.get("mode"), Guard.MODE_NORMAL);
                String reason = map.get("reason") == null ? "" : String.valueOf(map.get("reason"));
                guard = new Guard(mode, reason);
            }
        } catch (Exception e) {
            // 只记 debug：ops 没起来时这条会每个缓存周期刷一次，不该污染错误日志
            log.debug("成本闸门查询失败，按正常放行 tenant={} error={}", tenant, e.getMessage());
        }
        cache.put(tenant, new Entry(now, guard));
        return guard;
    }

    private static int intOf(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw == null ? fallback : Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private record Entry(long at, Guard guard) {
    }

    }
