package io.supportflow.ops.internal;

import io.supportflow.common.auth.AiRequestSigner;
import io.supportflow.ops.dto.AiTraceVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 服务的可观测查询客户端：按调用链号查明细、取单租户近 N 天成本。
 *
 * <p>为什么不复用 {@code AiMetricsClient}：那个类只服务「AI 监控」大盘，
 * 一旦往里塞第二类查询，两个页面的改动就会互相牵制。多一个小客户端，
 * 换来的是两处失败互不影响。</p>
 *
 * <p>拿不到数据一律返回空结构：可观测页宁可显示"暂时取不到"，
 * 也不能因为 AI 服务没起来就整页报错——告警与预算配置本身在 ops 库里，照常能看能改。</p>
 */
@Component
public class AiObservabilityClient {

    private static final Logger log = LoggerFactory.getLogger(AiObservabilityClient.class);

    private final String baseUrl;
    private final RestClient restClient;
    private final String aiSecret;

    public AiObservabilityClient(@Value("${supportflow.ai.internal-base-url:http://127.0.0.1:9100}") String baseUrl,
                                 @Value("${SUPPORTFLOW_AI_OPS_SECRET:}") String aiSecret) {
        this.baseUrl = baseUrl;
        this.aiSecret = aiSecret;
        this.restClient = RestClient.builder().build();
    }

    /** 按调用链号查这次请求在 AI 侧发生了什么 */
    public AiTraceVO trace(String traceId) {
        String path = baseUrl + "/api/internal/usage/trace/" + traceId;
        try {
            AiTraceVO body = get(path, "").retrieve().body(AiTraceVO.class);
            return body == null ? emptyTrace(traceId) : body;
        } catch (Exception e) {
            log.warn("按调用链号查 AI 计量失败 trace={} error={}", traceId, e.getMessage());
            return emptyTrace(traceId);
        }
    }

    /** 单租户近 N 天的成本与调用量（成本预算算使用率用） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> tenantTotal(String tenantCode, int days) {
        String path = baseUrl + "/api/internal/usage/tenant-total?tenantCode=" + tenantCode + "&days=" + days;
        try {
            Map<String, Object> body = get(path, tenantCode).retrieve().body(Map.class);
            if (body != null) {
                return body;
            }
        } catch (Exception e) {
            log.warn("取租户 AI 用量失败 tenant={} error={}", tenantCode, e.getMessage());
        }
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("tenantCode", tenantCode);
        empty.put("days", days);
        empty.put("cost", 0);
        empty.put("calls", 0);
        empty.put("tokens", 0);
        empty.put("available", false);
        return empty;
    }

    /**
     * 近 N 天按租户的指标（告警评估器按租户判断时用，一次查询出全量）。
     *
     * <p>拿不到返回空列表：这一轮评估就跳过按租户规则，而不是把上一轮的数字当成本轮。
     * 用旧数字判告警比不判更糟——会一直在同一条事件上反复报警。</p>
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> tenantMetrics(int days) {
        String path = baseUrl + "/api/internal/usage/tenant-metrics?days=" + days;
        try {
            Map<String, Object> body = get(path, "").retrieve().body(Map.class);
            Object items = body == null ? null : body.get("items");
            if (items instanceof List<?> list) {
                List<Map<String, Object>> result = new ArrayList<>(list.size());
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        result.add((Map<String, Object>) map);
                    }
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("取按租户指标失败 days={} error={}", days, e.getMessage());
        }
        return List.of();
    }

    private RestClient.RequestHeadersSpec<?> get(String path, String tenant) {
        URI uri = URI.create(path);
        return restClient.get().uri(uri)
                .header("X-Tenant-Code", tenant)
                .header(AiRequestSigner.HEADER,
                        AiRequestSigner.credential(aiSecret, "ops", HttpMethod.GET.name(), uri.getRawPath(), tenant));
    }

    private static AiTraceVO emptyTrace(String traceId) {
        return new AiTraceVO(false, List.of(), traceId);
    }
}
