package io.supportflow.customer.internal;

import io.supportflow.common.auth.AiRequestSigner;

import io.supportflow.common.context.TenantContext;
import io.supportflow.common.context.TraceContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.dto.bot.BotAiHealthVO;
import io.supportflow.customer.dto.bot.BotProfile;
import io.supportflow.customer.dto.cost.Guard;
import io.supportflow.customer.service.bot.BotBrainService;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * supportflow-ai「AI 客服大脑」客户端：意图识别 / 情绪识别 / 多轮状态 / 转人工决策都在 Python 侧。
 *
 * <p>为什么判断逻辑不放 Java：意图匹配要复用知识库那套中文片段切分，情绪和转人工策略
 * 又会不断调（加词、改阈值、换成模型判断），这些待在 Python 里改一版就能上线；
 * Java 侧只做它擅长的事——把消息落库、把会话状态流转、把结果推给长连接。</p>
 *
 * <p>调用失败一律抛业务异常，由 {@code BotBrainService} 决定"降级转人工"还是"直接放行"，
 * 绝不在这里假装成功——机器人答不出和机器人挂了，对客户来说是两回事。</p>
 */
@Component
public class BotAiClient {

    private static final Logger log = LoggerFactory.getLogger(BotAiClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String aiSecret;
    private final boolean logPayload;
    private final int timeoutSeconds;
    /** 计费用量：调一次客服大脑算一次 AI 调用 */
    private final BillingUsageCollector billingUsageCollector;
    /** 成本闸门：预算超限时按平台配置降级或暂停（执行点在发请求之前） */
    private final CostGuardClient costGuardClient;

    public BotAiClient(
            @Value("${SUPPORTFLOW_AI_CUSTOMER_SECRET:}") String aiSecret,
            @Value("${supportflow.ai.bot-base-url:http://127.0.0.1:9100}") String baseUrl,
            @Value("${supportflow.ai.log-payload:true}") boolean logPayload,
            @Value("${supportflow.ai.bot-timeout-seconds:60}") int timeoutSeconds,
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
        this.timeoutSeconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        this.billingUsageCollector = billingUsageCollector;
        this.costGuardClient = costGuardClient;
    }

    /**
     * 让客服大脑处理一轮对话。
     *
     * @param messages 会话历史（按时间正序，role 取 user / assistant）
     * @return 大脑的处理结果（reply / intent / emotion / need_human / transfer_reason 等，键名是下划线风格）
     */
    public Map<String, Object> think(String tenantCode, String sessionNo,
                                     List<Map<String, String>> messages, int topK) {
        // 成本闸门：预算超限时不再调模型。**降级也要把话接住**——返回一条降级回复，
        // 而不是抛异常（抛异常会让上层"AI 不可用 → 直接转人工"，那是把成本问题变成人工成本）。
        Guard guard = costGuardClient.check(tenantCode);
        if (guard.isDegraded()) {
            log.warn("按成本策略降级，本轮不调模型 tenant={} session={} reason={}",
                    tenantCode, sessionNo, guard.reason());
            return degradedReply(guard.reason());
        }
        if (guard.isBlocked()) {
            log.warn("按成本策略暂停 AI，转入人工 tenant={} session={} reason={}",
                    tenantCode, sessionNo, guard.reason());
            return blockedReply(guard.reason());
        }
        billingUsageCollector.countAiCall(tenantCode);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", sessionNo == null ? "" : sessionNo);
        body.put("tenant_code", tenantCode);
        body.put("messages", messages == null ? List.of() : messages);
        body.put("top_k", topK <= 0 ? 5 : topK);
        // 调用前先留一行：这样"卡在 AI 调用上"和"压根没调"在日志里能区分开
        log.info("调用客服大脑 tenant={} session={} 历史={}条 问题={}",
                tenantCode, sessionNo, messages == null ? 0 : messages.size(),
                lastQuestion(messages));
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ai/v1/agent/brain"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header(TenantContext.HEADER_TENANT_CODE, tenantCode)
                    // 调用链号：前端 → 网关 → 本服务 → AI 服务一路透传，AI 侧把它写进计量表
                    .header(TraceContext.HEADER_REQUEST_ID,
                            TraceContext.getOrCreate())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(AiRequestSigner.sign(request, aiSecret, "customer", tenantCode),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (logPayload) {
                log.info("客服大脑响应 tenant={} session={} status={} body={}",
                        tenantCode, sessionNo, response.statusCode(), safe(response.body()));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(50001, describeStatus(response.statusCode(), parseDetail(response.body())));
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {
            });
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(50001, "客服大脑调用失败：" + friendly(e));
        }
    }

    /**
     * 成本降级时给客户的回话（不调模型，但**不把客户丢给人工**）。
     *
     * <p>为什么返回一条正常形状的"大脑结果"而不是抛异常：抛异常会让上层走
     * "AI 不可用 → 直接转人工"，那是把成本问题变成了人工成本。
     * 这里保持会话在机器人手里，客户照样得到一句明确的答复。</p>
     */
    private static Map<String, Object> degradedReply(String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bot_enabled", true);
        result.put("reply", "抱歉，智能应答当前正在限流（" + safeReason(reason)
                + "）。您可以先描述问题，或回复「转人工」由人工客服处理。");
        result.put("need_human", false);
        result.put("intent", "其它");
        result.put("emotion", "中性");
        result.put("transfer_reason", null);
        result.put("engine", "cost-degrade");
        result.put("degraded", true);
        return result;
    }

    /** 成本暂停时：直接交给人工，客户不会没人管 */
    private static Map<String, Object> blockedReply(String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bot_enabled", true);
        result.put("reply", "抱歉，智能应答已暂停（" + safeReason(reason) + "），正在为您转接人工客服。");
        result.put("need_human", true);
        result.put("intent", "人工客服");
        result.put("emotion", "中性");
        result.put("transfer_reason", "成本策略暂停 AI");
        result.put("engine", "cost-block");
        result.put("degraded", true);
        return result;
    }

    private static String safeReason(String reason) {
        return reason == null || reason.isBlank() ? "AI 成本已达上限" : reason;
    }

    /**
     * 大脑探活 + 体检（转发 supportflow-ai 的 /agent/brain/health）。
     *
     * @return 体检结果；调不通时返回带 hint 的结果而不是抛异常——体检本身不该把调用方打挂
     */
    public BotAiHealthVO health() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ai/v1/agent/brain/health"))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(AiRequestSigner.sign(request, aiSecret, "customer", null),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String hint = null;
            if (response.statusCode() == 404) {
                hint = "supportflow-ai 里没有 /api/ai/v1/agent/brain 接口：它还是旧版本，请重启 supportflow-ai（或确认已拉最新代码）";
            } else if (response.statusCode() >= 300) {
                hint = describeStatus(response.statusCode(), parseDetail(response.body()));
            }
            Object detail = objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {
            }).get("detail");
            return new BotAiHealthVO(response.statusCode() >= 200 && response.statusCode() < 300,
                    response.statusCode(), baseUrl, hint, detail);
        } catch (Exception e) {
            return new BotAiHealthVO(false, null, baseUrl,
                    "连不上 supportflow-ai（" + friendly(e) + "），请确认它已启动，端口与 supportflow.ai.bot-base-url 一致",
                    null);
        }
    }

    /** 把 HTTP 状态码翻译成"下一步该干什么"，而不是只丢一个 404/500 给用户猜。 */
    private String describeStatus(int status, String detail) {
        if (status == 404) {
            return "AI 服务没有 /api/ai/v1/agent/brain 接口（服务是旧版本，请重启 supportflow-ai）";
        }
        if (status >= 500) {
            return "AI 服务内部报错：" + detail;
        }
        return "AI 服务返回 " + status + "：" + detail;
    }

    /**
     * 取租户配置的欢迎语（进线时发一条，之后不再重复）。
     *
     * <p>为什么由 Java 来取：欢迎语存在 AI 侧的 bot_setting，而"客户进线"这件事发生在
     * customer-service。让开场白跟着会话开场走，而不是粘在第一条回答前面，
     * 客户体验才是"先说欢迎、再认真回答"。</p>
     *
     * <p>结果缓存 60 秒：欢迎语是低频变更的配置，没必要每开一个会话就多打一次 HTTP。</p>
     *
     * @return 欢迎语；没配置或 AI 服务不可用时返回 null（调用方退回一句通用系统提示）
     */
    public BotProfile botProfile(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return BotProfile.EMPTY;
        }
        long now = System.currentTimeMillis();
        Long cachedAt = greetingCacheAt.get(tenantCode);
        if (cachedAt != null && now - cachedAt < GREETING_CACHE_MILLIS) {
            return greetingCache.getOrDefault(tenantCode, BotProfile.EMPTY);
        }
        BotProfile profile = BotProfile.EMPTY;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ai/v1/bot/settings"))
                    // 超时给得短：这句欢迎语是在"客户开会话"路径上取的，
                    // AI 服务不可用时宁可立刻退回通用提示，也不能让客户等着开门
                    .timeout(Duration.ofSeconds(3))
                    .header(TenantContext.HEADER_TENANT_CODE, tenantCode)
                    .GET()
                    .build();
            String body = httpClient.send(AiRequestSigner.sign(request, aiSecret, "customer", tenantCode), HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8)).body();
            Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) parsed.get("data");
            if (data != null) {
                profile = new BotProfile(text(data.get("botName")), text(data.get("welcomeMessage")),
                        text(data.get("avatarUrl")));
            }
        } catch (Exception e) {
            // 取不到不是错误：退回中性称呼即可，别让开场白拖住会话
            log.warn("读取机器人配置失败 tenant={} error={}", tenantCode, e.getMessage());
        }
        greetingCache.put(tenantCode, profile);
        greetingCacheAt.put(tenantCode, now);
        return profile;
    }

    /** 取值并去掉空白；null 转成 null（调用方用默认值兜） */
    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String trimmed = String.valueOf(value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

        /** 机器人档案缓存：配置改动低频，60 秒内不重复问 AI 服务 */
    private final Map<String, BotProfile> greetingCache = new ConcurrentHashMap<>();
    private final Map<String, Long> greetingCacheAt = new ConcurrentHashMap<>();
    private static final long GREETING_CACHE_MILLIS = 60_000L;

    /** 日志里显示"这一轮客户问的是什么"：取最后一条 user 消息的前 60 字 */
    private String lastQuestion(List<Map<String, String>> messages) {
        if (messages == null || messages.isEmpty()) {
            return "-";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, String> item = messages.get(i);
            if ("user".equals(item.get("role"))) {
                return preview(item.get("content"));
            }
        }
        return preview(messages.get(messages.size() - 1).get("content"));
    }

    /** 内容预览：换行压平、超长截断 */
    private String preview(String content) {
        if (content == null || content.isBlank()) {
            return "-";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
    }

    /** 组装一条会话消息（Java 侧只负责角色映射，判断交给 Python）。 */
    public static Map<String, String> message(String role, String content) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("role", role);
        item.put("content", content == null ? "" : content);
        return item;
    }

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

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 600 ? value.substring(0, 600) + "..." : value;
    }

    private String friendly(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("Connection refused") || message.contains("I/O error")) {
            return "AI 服务（supportflow-ai）没启动";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
