package io.supportflow.customer.internal;

import io.supportflow.common.context.TenantContext;
import io.supportflow.common.context.TraceContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.dto.channel.AsrResult;
import io.supportflow.customer.dto.channel.TtsResult;
import io.supportflow.customer.dto.cost.Guard;
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
import java.util.Map;

/**
 * supportflow-ai「语音」客户端：一句话识别（ASR）与语音合成（TTS）。
 *
 * <p>阿里云 NLS 的 AccessKey 只在 supportflow-ai（Python）那一侧，Java 侧只认两个接口，
 * 不在业务服务里再存一份密钥——这和企业智能招聘系统一样：语音调用收口在一处，
 * 业务只关心"这段录音的文字是什么""这段文字读出来是什么"。</p>
 *
 * <p>两个接口的失败口径不同，这里如实区分：</p>
 * <ul>
 *   <li><b>没配置语音能力</b>：返回 {@code available=false}，不是异常——
 *       录音照样存着，页面显示"未配置语音能力"，不该把一次正常通话标成故障；</li>
 *   <li><b>调用了但失败</b>（网络、模型报错、音频不合法）：抛 {@link BizException}，
 *       由调用方决定是重试还是记成"转写失败"。</li>
 * </ul>
 */
@Component
public class SpeechAiClient {

    private static final Logger log = LoggerFactory.getLogger(SpeechAiClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final boolean logPayload;
    private final int timeoutSeconds;
    /** 计费用量：调一次语音算一次 AI 调用（配额口径与视觉/文本一致） */
    private final BillingUsageCollector billingUsageCollector;
    /** 成本闸门：预算超限时不调语音模型 */
    private final CostGuardClient costGuardClient;

    public SpeechAiClient(
            @Value("${supportflow.ai.speech-base-url:http://127.0.0.1:9100}") String baseUrl,
            @Value("${supportflow.ai.log-payload:true}") boolean logPayload,
            @Value("${supportflow.ai.speech-timeout-seconds:60}") int timeoutSeconds,
            BillingUsageCollector billingUsageCollector,
            CostGuardClient costGuardClient
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank())
                ? "http://127.0.0.1:9100" : baseUrl.replaceAll("/+$", "");
        this.logPayload = logPayload;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        this.billingUsageCollector = billingUsageCollector;
        this.costGuardClient = costGuardClient;
    }

            /**
     * 一句话识别：16k 单声道 WAV 字节 → 文本。
     *
     * @param audioFormat 音频格式（wav / pcm / mp3…，与 supportflow-ai 侧配置的默认值一致时可传空）
     */
    public AsrResult recognize(String tenantCode, byte[] audio, String audioFormat, int sampleRate) {
        if (audio == null || audio.length == 0) {
            return new AsrResult(false, "", "录音内容为空");
        }
        Guard guard = costGuardClient.check(tenantCode);
        if (guard.skipModel()) {
            log.warn("按成本策略跳过语音识别 tenant={} reason={}", tenantCode, guard.reason());
            return new AsrResult(false, "", "已按成本策略跳过语音识别：" + guard.reason());
        }
        billingUsageCollector.countAiCall(tenantCode);
        String query = "/api/ai/v1/speech/asr";
        if (audioFormat != null && !audioFormat.isBlank()) {
            query += "?format=" + audioFormat + "&sample_rate=" + (sampleRate <= 0 ? 16000 : sampleRate);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + query))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/octet-stream")
                    .header(TenantContext.HEADER_TENANT_CODE, tenantCode == null ? "" : tenantCode)
                    .header(TraceContext.HEADER_REQUEST_ID,
                            TraceContext.getOrCreate())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(audio))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(50001, parseDetail(response.body()));
            }
            Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {
            });
            boolean available = Boolean.TRUE.equals(body.get("available"));
            String text = body.get("text") == null ? "" : String.valueOf(body.get("text"));
            String hint = body.get("hint") == null ? "" : String.valueOf(body.get("hint"));
            if (logPayload) {
                log.info("语音识别响应 tenant={} 可用={} 文本={}字 hint={}",
                        tenantCode, available, text.length(), hint);
            }
            return new AsrResult(available, text, hint);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(50001, "语音识别调用失败：" + friendly(e));
        }
    }

    /**
     * 语音合成：文本 → 音频字节。
     *
     * @param voice  音色，留空用平台默认（知祥）
     * @param format 音频格式，留空用平台默认（wav）
     */
    public TtsResult synthesize(String tenantCode, String text, String voice, String format) {
        if (text == null || text.isBlank()) {
            return new TtsResult(false, new byte[0], "", "要合成的文本为空");
        }
        Guard guard = costGuardClient.check(tenantCode);
        if (guard.skipModel()) {
            log.warn("按成本策略跳过语音合成 tenant={} reason={}", tenantCode, guard.reason());
            return new TtsResult(false, new byte[0], "", "已按成本策略跳过语音合成：" + guard.reason());
        }
        billingUsageCollector.countAiCall(tenantCode);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text);
        payload.put("tenant_code", tenantCode == null ? "" : tenantCode);
        if (voice != null && !voice.isBlank()) {
            payload.put("voice", voice);
        }
        if (format != null && !format.isBlank()) {
            payload.put("format", format);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ai/v1/speech/tts"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header(TenantContext.HEADER_TENANT_CODE, tenantCode == null ? "" : tenantCode)
                    .header(TraceContext.HEADER_REQUEST_ID,
                            TraceContext.getOrCreate())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(50001, parseDetail(new String(response.body(), StandardCharsets.UTF_8)));
            }
            // 没配置语音能力时接口回的是 JSON（available=false），不是音频——
            // 这里靠 Content-Type 分辨，不能只看状态码
            if (contentType.toLowerCase().contains("application/json")) {
                Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {
                });
                String hint = body.get("hint") == null ? "语音能力不可用" : String.valueOf(body.get("hint"));
                log.info("语音合成不可用 tenant={} hint={}", tenantCode, hint);
                return new TtsResult(false, new byte[0], "", hint);
            }
            byte[] audio = response.body();
            if (audio == null || audio.length == 0) {
                throw new BizException(50001, "语音合成返回了空音频");
            }
            log.info("语音合成响应 tenant={} {}-{}字节 {}字",
                    tenantCode, audio.length, contentType, text.length());
            return new TtsResult(true, audio,
                    contentType.isBlank() ? "audio/wav" : contentType, "");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(50001, "语音合成调用失败：" + friendly(e));
        }
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
        return body == null ? "" : (body.length() > 400 ? body.substring(0, 400) + "..." : body);
    }

    private String friendly(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("Connection refused") || message.contains("I/O error")) {
            return "AI 服务（supportflow-ai）没启动";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
