package io.supportflow.customer.internal;

import io.supportflow.common.auth.AiRequestSigner;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.context.TenantContext;
import io.supportflow.common.context.TraceContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.dto.cost.Guard;
import io.supportflow.customer.dto.quality.EvaluateRequest;
import io.supportflow.customer.dto.quality.EvaluateResult;
import io.supportflow.customer.service.metrics.BillingUsageCollector;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * supportflow-ai 质检服务客户端（使用 JDK HttpClient，确保 JSON body 真实发送）。
 */
@Component
public class QaAiClient {

    private static final Logger log = LoggerFactory.getLogger(QaAiClient.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String aiSecret;
    private final boolean logPayload;
    /** 计费用量：调一次质检算一次 AI 调用 */
    private final BillingUsageCollector billingUsageCollector;
    /** 成本闸门：预算超限时跳过质检的模型调用 */
    private final CostGuardClient costGuardClient;

    public QaAiClient(
            @Value("${SUPPORTFLOW_AI_CUSTOMER_SECRET:}") String aiSecret,
            @Value("${supportflow.ai.qa-base-url:http://127.0.0.1:9100}") String baseUrl,
            @Value("${supportflow.ai.log-payload:true}") boolean logPayload,
            BillingUsageCollector billingUsageCollector,
            CostGuardClient costGuardClient
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.aiSecret = aiSecret;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "http://127.0.0.1:9100" : baseUrl.replaceAll("/+$", "");
        this.logPayload = logPayload;
        this.billingUsageCollector = billingUsageCollector;
        this.costGuardClient = costGuardClient;
    }

    public EvaluateResult evaluate(EvaluateRequest request) {
        // 成本闸门：质检是"事后加分项"，成本紧张时优先保对话；跳过时如实说明原因，
        // 由调用方把任务标成"因成本策略跳过"，而不是假装质检通过
        Guard guard = costGuardClient.check(request.tenantCode());
        if (guard.skipModel()) {
            throw new BizException(40303,
                    "按成本策略跳过 AI 质检：" + guard.reason());
        }
        billingUsageCollector.countAiCall(request.tenantCode());
        // 调用链号优先用当前请求的：这样"页面上的这次操作"与"这次质检"能对上号
        String traceId = TraceContext.getOrCreate();
        long startedAt = System.currentTimeMillis();
        int ruleCount = request.ruleNames() == null ? 0 : request.ruleNames().size();
        int transcriptChars = request.transcript() == null ? 0 : request.transcript().length();
        log.info("调用 AI 质检开始 trace={} tenant={} session={} agent={} rules={} transcriptChars={}",
                traceId, request.tenantCode(), request.sessionName(), request.agentName(),
                ruleCount, transcriptChars);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenant_code", request.tenantCode());
            body.put("session_name", request.sessionName());
            body.put("agent_name", request.agentName());
            body.put("transcript", request.transcript());
            body.put("rule_names", request.ruleNames());
            body.put("rules", request.rules() == null ? List.of() : request.rules());

            String payload = objectMapper.writeValueAsString(body);
            if (logPayload) {
                log.info("AI 质检请求参数 trace={} body={}", traceId, payload);
            }
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ai/v1/qa/evaluate"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header(TenantContext.HEADER_TENANT_CODE, request.tenantCode())
                    .header(TraceContext.HEADER_REQUEST_ID, traceId)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    AiRequestSigner.sign(httpRequest, aiSecret, "customer", request.tenantCode()),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            long cost = System.currentTimeMillis() - startedAt;
            if (logPayload) {
                log.info("AI 质检响应参数 trace={} status={} cost={}ms body={}",
                        traceId, response.statusCode(), cost, response.body());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("调用 AI 质检失败 trace={} tenant={} session={} status={} cost={}ms reason={}",
                        traceId, request.tenantCode(), request.sessionName(),
                        response.statusCode(), cost, safe(response.body()));
                return fallback(request, "HTTP " + response.statusCode() + ": " + response.body());
            }
            ApiResponse<EvaluateResult> parsed = objectMapper.readValue(
                    response.body(),
                    new TypeReference<>() {
                    }
            );
            if (parsed == null || parsed.code() != 0 || parsed.data() == null) {
                log.warn("调用 AI 质检异常 trace={} tenant={} session={} status={} cost={}ms reason={}",
                        traceId, request.tenantCode(), request.sessionName(),
                        response.statusCode(), cost, parsed == null ? "AI 质检无响应" : parsed.message());
                return fallback(request, parsed == null ? "AI 质检无响应" : parsed.message());
            }
            EvaluateResult result = parsed.data();
            log.info("调用 AI 质检成功 trace={} tenant={} session={} cost={}ms score={} risk={} hitRules={} source={}",
                    traceId, request.tenantCode(), request.sessionName(), cost,
                    result.aiScore(), result.riskLevel(),
                    result.rules() == null ? 0 : result.rules().size(), result.source());
            return result;
        } catch (Exception e) {
            log.error("调用 AI 质检异常 trace={} tenant={} session={} cost={}ms error={}",
                    traceId, request.tenantCode(), request.sessionName(),
                    System.currentTimeMillis() - startedAt, e.toString(), e);
            return fallback(request, e.getMessage());
        }
    }

    private EvaluateResult fallback(EvaluateRequest request, String reason) {
        log.warn("AI 质检降级为规则兜底 tenant={} session={} reason={}",
                request.tenantCode(), request.sessionName(), safe(reason));
        return new EvaluateResult(
                80,
                1,
                List.of(),
                "AI 质检服务暂不可用，已使用规则引擎兜底：" + safe(reason),
                "fallback-rule"
        );
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "未配置或调用失败";
        }
        return value.length() > 160 ? value.substring(0, 160) : value;
    }
}
