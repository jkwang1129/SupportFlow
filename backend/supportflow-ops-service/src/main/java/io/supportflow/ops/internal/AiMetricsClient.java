package io.supportflow.ops.internal;

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
import java.util.List;
import java.util.Map;
import io.supportflow.ops.dto.AiLlmStatusVO;
import io.supportflow.ops.dto.AiUsageMetricsVO;

/**
 * AI 服务内部客户端：取近 N 天的 AI 用量大盘（调用量 / token / 成本 / 耗时 / 成功率）。
 *
 * <p>为什么由 ops 代一层：AI 服务（Python，9100）只认"内部调用"，
 * 不做 JWT 与角色校验；平台页面要读它，必须先在 Java 侧过一遍"是不是平台账号"，
 * 否则企业账号带自己的令牌就能看到全平台的成本数据。</p>
 *
 * <p>失败返回空 Map：AI 监控页会把这一块显示成"暂无数据"，不影响另外两块
 * （质量与安全、租户配额各有自己的接口）。</p>
 */
@Component
public class AiMetricsClient {

    private static final Logger log = LoggerFactory.getLogger(AiMetricsClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public AiMetricsClient(@Value("${supportflow.ai.internal-base-url:http://127.0.0.1:9100}") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
    }

    /**
     * AI 用量大盘。
     *
     * @return 拿不到返回 null（调用方据此显示"暂无数据"，而不是把它当成"全是 0"）
     */
    public AiUsageMetricsVO metrics(int days) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/internal/usage/metrics?days=" + days))
                    .timeout(Duration.ofSeconds(6))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("AI 用量接口返回 {}：{}", response.statusCode(),
                        response.body() == null ? "" : response.body().substring(0,
                                Math.min(200, response.body().length())));
                return null;
            }
            return MAPPER.readValue(response.body(), AiUsageMetricsVO.class);
        } catch (Exception e) {
            log.warn("取 AI 用量失败（AI 监控的成本块显示暂无数据）：{}", e.getMessage());
            return null;
        }
    }

    /** 计量状态：AI 服务当前用的是真模型还是降级（没配密钥时页面要给出准确解释） */
    public AiLlmStatusVO status() {
        return simpleGet("/api/internal/usage/status", AiLlmStatusVO.class);
    }

    /** 拿不到返回 null（页面按"没有状态"展示，而不是编一个默认值） */
    private <T> T simpleGet(String path, Class<T> type) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("AI 接口 {} 返回 {}", path, response.statusCode());
                return null;
            }
            return MAPPER.readValue(response.body(), type);
        } catch (Exception e) {
            log.warn("取 AI 接口 {} 失败：{}", path, e.getMessage());
            return null;
        }
    }

    /** 便捷方法：把 items 数组转成 List（内部接口都是 {items:[...]} 的形状） */
    public static List<Map<String, Object>> items(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }
}
