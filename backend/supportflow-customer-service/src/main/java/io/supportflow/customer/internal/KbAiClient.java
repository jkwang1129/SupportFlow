package io.supportflow.customer.internal;

import io.supportflow.common.auth.AiRequestSigner;

import io.supportflow.common.context.TenantContext;
import io.supportflow.common.context.TraceContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.dto.bot.IndexResult;
import io.supportflow.customer.dto.cost.Guard;
import io.supportflow.customer.service.metrics.BillingUsageCollector;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * supportflow-ai 知识库客户端：解析 / 切块 / 向量化 / 检索都在 Python 侧做，这里只负责调。
 *
 * <p>为什么不在 Java 里做：文档解析要处理 docx（zip+XML）、pdf（压缩流），
 * 向量化要接 embedding 模型，这些在 Python 生态里是现成的；Java 侧专注做
 * 文档台账（kb_document）与权限、租户隔离。</p>
 *
 * <p>数据边界：kb_chunk 由 Python 写、Java 读；调用失败一律抛业务异常，
 * 让上传的人知道"索引没成功"，而不是悄悄存成一份检索不到的文档。</p>
 */
@Component
public class KbAiClient {

    private static final Logger log = LoggerFactory.getLogger(KbAiClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String aiSecret;
    private final boolean logPayload;
    /** 计费用量：知识库索引（embedding）与检索 / 问答都算 AI 调用 */
    private final BillingUsageCollector billingUsageCollector;
    /** 成本闸门：预算超限时按平台配置跳过模型调用 */
    private final CostGuardClient costGuardClient;

    public KbAiClient(
            @Value("${SUPPORTFLOW_AI_CUSTOMER_SECRET:}") String aiSecret,
            @Value("${supportflow.ai.kb-base-url:http://127.0.0.1:9100}") String baseUrl,
            @Value("${supportflow.ai.log-payload:true}") boolean logPayload,
            BillingUsageCollector billingUsageCollector,
            CostGuardClient costGuardClient
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.aiSecret = aiSecret;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank())
                ? "http://127.0.0.1:9100" : baseUrl.replaceAll("/+$", "");
        this.logPayload = logPayload;
        this.billingUsageCollector = billingUsageCollector;
        this.costGuardClient = costGuardClient;
    }

    /**
     * 索引一份文件：把原始字节交给 Python，由它解析、切块、向量化、落库。
     *
     * @return 索引结果（切片数、字符数、向量来源、前几块预览）
     */
    public IndexResult indexFile(String tenantCode, long docId, String fileName, byte[] payload) {
        billingUsageCollector.countAiCall(tenantCode);
        if (payload == null || payload.length == 0) {
            // 明确挡住：以前这里会发出一个 0 字节的请求，AI 服务只能回一句"文件是空的"，
            // 光看那句提示根本猜不到是上传环节丢的内容
            throw new BizException(40001, "上传的文件是空的，请重新选择文件");
        }
        String url = baseUrl + "/api/ai/v1/kb/index"
                + "?tenant_code=" + enc(tenantCode)
                + "&doc_id=" + docId
                + "&file_name=" + enc(fileName);
        log.info("调用知识库索引 tenant={} docId={} file={} bytes={}",
                tenantCode, docId, fileName, payload.length);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/octet-stream")
                    .header(TenantContext.HEADER_TENANT_CODE, tenantCode)
                    .header("X-File-Name", enc(fileName))
                    // 关键：显式把字节数组作为请求体，不经过消息转换器
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(AiRequestSigner.sign(request, aiSecret, "customer", tenantCode),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (logPayload) {
                log.info("知识库索引响应 tenant={} docId={} status={} body={}",
                        tenantCode, docId, response.statusCode(), safe(response.body()));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(40001, parseDetail(response.body()));
            }
            return readResult(response.body());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(50001, "文档索引失败：" + friendly(e));
        }
    }

    /**
     * 索引一段正文（手工录入 / 编辑后的文档）。
     */
    public IndexResult indexText(String tenantCode, long docId, String content) {
        billingUsageCollector.countAiCall(tenantCode);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docId", docId);
        body.put("content", content);
        try {
            String response = postJson(baseUrl + "/api/ai/v1/kb/index-text?tenant_code=" + enc(tenantCode),
                    body, tenantCode);
            if (logPayload) {
                log.info("知识库索引（正文）响应 tenant={} docId={} body={}", tenantCode, docId, safe(response));
            }
            return readResult(response);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(50001, "正文索引失败：" + friendly(e));
        }
    }

    /**
     * 检索：返回命中的切片（带相似度、所属文档）。
     */
    public List<Map<String, Object>> search(String tenantCode, String query, int topK) {
        billingUsageCollector.countAiCall(tenantCode);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", tenantCode);
        body.put("query", query);
        body.put("topK", topK);
        try {
            String response = postJson(baseUrl + "/api/ai/v1/kb/search", body, tenantCode);
            if (logPayload) {
                log.info("知识库检索响应 tenant={} query={} body={}", tenantCode, query, safe(response));
            }
            Map<String, Object> parsed = objectMapper.readValue(response, new TypeReference<>() {
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) parsed.get("results");
            return results == null ? List.of() : results;
        } catch (Exception e) {
            throw new BizException(50001, "知识检索失败：" + friendly(e));
        }
    }

    /**
     * 知识问答：把问题交给 RAG 编排（检索 → 判断 → 生成 → 校验引用）。
     *
     * @return 原始响应（answer / citations / steps 等，键名是下划线风格）
     */
    public Map<String, Object> ask(String tenantCode, String question, int topK) {
        // 成本闸门：超预算时不调模型，返回一句如实说明的降级答案（引用为空，不编来源）
        Guard guard = costGuardClient.check(tenantCode);
        if (guard.skipModel()) {
            log.warn("按成本策略跳过知识问答的模型调用 tenant={} reason={}", tenantCode, guard.reason());
            Map<String, Object> degraded = new LinkedHashMap<>();
            degraded.put("answer", "抱歉，智能问答当前因成本策略暂停（" + guard.reason()
                    + "）。可以稍后再试，或转人工客服处理。");
            degraded.put("citations", List.of());
            degraded.put("steps", List.of("cost-guard"));
            degraded.put("engine", "cost-degrade");
            degraded.put("degraded", true);
            return degraded;
        }
        billingUsageCollector.countAiCall(tenantCode);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", tenantCode);
        body.put("question", question);
        body.put("topK", topK);
        try {
            String response = postJson(baseUrl + "/api/ai/v1/rag/ask", body, tenantCode);
            if (logPayload) {
                log.info("知识问答响应 tenant={} question={} body={}",
                        tenantCode, question, safe(response));
            }
            return objectMapper.readValue(response, new TypeReference<>() {
            });
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(50001, "知识问答失败：" + friendly(e));
        }
    }

    /**
     * 删除某个文档的全部切片（文档删除 / 下线时调用）。
     */
    public void deleteChunks(String tenantCode, long docId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ai/v1/kb/documents/" + docId
                            + "?tenant_code=" + enc(tenantCode)))
                    .timeout(Duration.ofSeconds(30))
                    .header(TenantContext.HEADER_TENANT_CODE, tenantCode)
                    .DELETE()
                    .build();
            httpClient.send(AiRequestSigner.sign(request, aiSecret, "customer", tenantCode), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            // 删切片失败不该拦住删文档：切片留着也只是查不到（文档已软删）
            log.warn("删除知识切片失败 tenant={} docId={} error={}", tenantCode, docId, e.getMessage());
        }
    }

    /** 健康检查结果缓存：总览页每次刷新都探一次活，会白等一个 RTT（AI 服务挂了要等 5 秒） */
    private volatile boolean healthCache = false;
    private volatile long healthCacheAt = 0L;
    /** 缓存有效期：30 秒内不再重复探活 */
    private static final long HEALTH_CACHE_MILLIS = 30_000L;

    /** 知识库探活（概览页展示"向量库是否可用"）；30 秒内走缓存。 */
    public boolean healthy() {
        long now = System.currentTimeMillis();
        if (now - healthCacheAt < HEALTH_CACHE_MILLIS) {
            return healthCache;
        }
        boolean result = probe();
        healthCache = result;
        healthCacheAt = now;
        return result;
    }

    /**
     * 向量体检：库里那批切片到底是用什么向量建出来的。
     *
     * <p>用来判断"要不要重新索引"：向量是**索引那一刻**算出来的，
     * 当时密钥没配好（或远程调用 401）就会写成没有语义的 local-hash 兜底向量，
     * 事后光配密钥、光重启都不会重算——必须重新索引。</p>
     *
     * @return health 原始 JSON；读不到时返回空 Map（调用方按"不确定"处理，不乱动数据）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> healthDetail(String tenantCode) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ai/v1/kb/repair-status"))
                    .header(TenantContext.HEADER_TENANT_CODE, tenantCode)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            var response = httpClient.send(AiRequestSigner.sign(request, aiSecret, "customer", tenantCode),
                    HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            if (response.statusCode() != 200 || body == null || body.isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("知识库向量体检失败：{}", e.getMessage());
            return Map.of();
        }
    }

    private boolean probe() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ai/v1/kb/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            String body = httpClient.send(AiRequestSigner.sign(request, aiSecret, "customer", null), HttpResponse.BodyHandlers.ofString()).body();
            return body != null && body.contains("\"UP\"");
        } catch (Exception e) {
            log.warn("知识库探活失败：{}", e.getMessage());
            return false;
        }
    }

    /** POST JSON：显式按 UTF-8 编码请求体，避免中文被写坏。 */
    private String postJson(String url, Map<String, Object> body, String tenantCode) throws Exception {
        String payload = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header(TenantContext.HEADER_TENANT_CODE, tenantCode)
                // 调用链号：AI 侧把它写进计量表，页面报错时按这个号能查到这一次
                .header(TraceContext.HEADER_REQUEST_ID,
                        TraceContext.getOrCreate())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(AiRequestSigner.sign(request, aiSecret, "customer", tenantCode),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BizException(40001, parseDetail(response.body()));
        }
        return response.body();
    }

    /** 从 AI 服务的错误响应里抠出可读原因（FastAPI 的 detail 字段）。 */
    private String parseDetail(String body) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {
            });
            Object detail = parsed.get("detail");
            if (detail != null) {
                return String.valueOf(detail);
            }
        } catch (Exception ignored) {
            // 解析不出来就退回原文
        }
        return safe(body);
    }

    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 400 ? value.substring(0, 400) + "..." : value;
    }

    private IndexResult readResult(String body) throws Exception {
        Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {
        });
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> preview = (List<Map<String, Object>>) parsed.getOrDefault("preview", List.of());
        return new IndexResult(
                str(parsed.get("docId")),
                str(parsed.get("fileName")),
                intOf(parsed.get("charCount")),
                intOf(parsed.get("chunkCount")),
                intOf(parsed.get("tokenCount")),
                str(parsed.get("embeddingModel")),
                str(parsed.get("embeddingSource")),
                preview == null ? List.of() : preview);
    }

    /** 把底层异常翻译成用户能看懂的一句话。 */
    private String friendly(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("Connection refused") || message.contains("I/O error")) {
            return "AI 服务（supportflow-ai）没启动，请先启动它再试";
        }
        if (message.contains("400")) {
            return message.replaceAll("^.*400[^\\]]*\\]?\\s*", "");
        }
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int intOf(Object value) {
        if (value == null) {
            return 0;
        }
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    }
