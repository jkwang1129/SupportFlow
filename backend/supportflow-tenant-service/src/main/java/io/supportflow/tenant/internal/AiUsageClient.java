package io.supportflow.tenant.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 服务内部客户端：取"某天各租户的 AI 调用量"。
 *
 * <p>为什么要跨服务问：AI 调用计量表（ai_db.ai_usage_record）是 Python AI 服务的库，
 * 配额却存在 tenant_db。跨库读别人的表是硬伤，所以由 AI 服务提供内部接口、
 * tenant-service 拉过来跟配额拼成"配额使用率"给平台看。</p>
 *
 * <p>拿不到就返回空列表：配额视图退化成"只有限额、没有用量"，
 * 总比整页报错强（AI 服务是可选依赖，不配密钥时它是 mock，也不影响其它功能）。</p>
 */
@Component
public class AiUsageClient {

    private static final Logger log = LoggerFactory.getLogger(AiUsageClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public AiUsageClient(@Value("${supportflow.ai.internal-base-url:http://127.0.0.1:9100}") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
    }

    /** 某天各租户的 AI 用量：{tenantCode → {calls, tokens, cost, failedCalls}} */
    public Map<String, Map<String, Object>> tenantUsage(LocalDate statDate) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/internal/usage/tenants?stat_date=" + statDate))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("AI 用量接口返回 {}：{}", response.statusCode(), response.body());
                return Map.of();
            }
            Map<String, Object> body = MAPPER.readValue(response.body(),
                    new TypeReference<Map<String, Object>>() {
                    });
            Object items = body.get("items");
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            if (items instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> row) {
                        Object tenant = row.get("tenantCode");
                        if (tenant != null) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typed = (Map<String, Object>) row;
                            result.put(String.valueOf(tenant), typed);
                        }
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("取 AI 用量失败（配额视图只显示限额）：{}", e.getMessage());
            return Map.of();
        }
    }
}
