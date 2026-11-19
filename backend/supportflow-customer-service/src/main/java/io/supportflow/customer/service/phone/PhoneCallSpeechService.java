package io.supportflow.customer.service.phone;

import io.supportflow.common.context.TenantContext;
import io.supportflow.customer.dto.channel.AsrResult;
import io.supportflow.customer.dto.channel.TtsResult;
import io.supportflow.customer.entity.Channel;
import io.supportflow.customer.internal.SpeechAiClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 400 热线语音编排：录音回传 → 下载 → 一句话识别 → 回填通话记录；IVR 播报 → 合成音频。
 *
 * <p>为什么要异步：录音通常比挂机事件晚几秒到几十秒推过来，而 webhook 必须秒回——
 * 话务平台等着响应去播下一句，不能因为它等我们下载 + 识别十几秒就超时重推。
 * 所以 webhook 只负责"记下录音地址 + 提交转写任务"，识别在独立线程池里做，
 * 结果通过 {@link PhoneCallService#markAsr} 回填。</p>
 *
 * <p>为什么异步线程要显式绑租户上下文：库上有 RLS 兜底（策略读 {@code app.tenant_code}），
 * 这个值是借连接时从 {@link TenantContext} 取的——转写线程没有请求上下文、也不会继承，
 * 不绑的话回填的那条 update 会被 RLS 静默过滤掉（不报错，但页面永远看不到转写）。
 * 现在用 {@link TenantContext#bind} 在作用域内绑定，出了作用域自动失效，不需要手动清理。</p>
 */
@Service
public class PhoneCallSpeechService {

    private static final Logger log = LoggerFactory.getLogger(PhoneCallSpeechService.class);

    /** 转写线程池：小队列 + 固定线程，话务高峰时宁可拒绝也不要拖垮服务 */
    private static final int POOL_SIZE = 2;
    private static final int QUEUE_SIZE = 200;

    private final SpeechAiClient speechAiClient;
    private final PhoneCallService phoneCallService;
    private final HttpClient httpClient;
    private final long maxAudioBytes;
    private final ExecutorService executor;

    public PhoneCallSpeechService(
            SpeechAiClient speechAiClient,
            PhoneCallService phoneCallService,
            @Value("${supportflow.ai.speech-max-audio-mb:10}") int maxAudioMb,
            @Value("${supportflow.ai.speech-timeout-seconds:60}") int timeoutSeconds
    ) {
        this.speechAiClient = speechAiClient;
        this.phoneCallService = phoneCallService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.maxAudioBytes = Math.max(1, maxAudioMb) * 1024L * 1024L;
        AtomicInteger seq = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(POOL_SIZE, runnable -> {
            Thread thread = new Thread(runnable, "phone-asr-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 提交一个转写任务（不阻塞 webhook）；入队失败也要留痕，不能静默丢 */
    public void transcribeLater(Channel channel, String callId, String recordingUrl) {
        if (recordingUrl == null || recordingUrl.isBlank()) {
            TenantContext.run(channel.getTenantCode(), () ->
                    phoneCallService.markAsr(channel, callId, false, "", "话务侧没有回传录音地址"));
            return;
        }
        try {
            executor.execute(() -> transcribe(channel, callId, recordingUrl));
        } catch (RejectedExecutionException e) {
            log.warn("语音转写队列已满 tenant={} callId={}", channel.getTenantCode(), callId);
            TenantContext.run(channel.getTenantCode(), () ->
                    phoneCallService.markAsr(channel, callId, false, "", "语音转写任务排队已满，请稍后重试"));
        }
    }

    /**
     * 转写入口（跑在转写线程里）。
     *
     * <p>转写线程没有请求上下文，所以这里显式把租户绑进作用域。用 ScopedValue 的 bind
     * 而不是 ThreadLocal 的 set/clear：出了作用域自动失效，不用再写 finally 清理，
     * 也就不会"漏清一次、把上个租户带进下一个转写任务"。</p>
     */
    void transcribe(Channel channel, String callId, String recordingUrl) {
        String tenantCode = channel.getTenantCode();
        try {
            TenantContext.run(tenantCode, () -> transcribeInTenant(channel, callId, recordingUrl, tenantCode));
        } catch (Exception e) {
            log.warn("400 语音转写异常 tenant={} callId={} error={}", tenantCode, callId, e.getMessage());
            try {
                TenantContext.run(tenantCode, () ->
                        phoneCallService.markAsr(channel, callId, false, "", "转写异常：" + friendly(e)));
            } catch (Exception ignored) {
                // 回填失败只记日志：不能因为"记录失败原因"再抛一层
            }
        }
    }

    /** 真的下载 + 识别 + 回填（租户已由调用方绑进作用域） */
    private void transcribeInTenant(Channel channel, String callId, String recordingUrl,
                                    String tenantCode) {
        byte[] audio;
        try {
            audio = download(recordingUrl);
        } catch (Exception e) {
            log.warn("400 录音下载失败 tenant={} callId={} url={} error={}",
                    tenantCode, callId, safeUrl(recordingUrl), e.getMessage());
            phoneCallService.markAsr(channel, callId, false, "",
                    "录音下载失败：" + friendly(e));
            return;
        }
        if (audio.length == 0) {
            phoneCallService.markAsr(channel, callId, false, "", "录音文件为空");
            return;
        }
        AsrResult result;
        try {
            result = speechAiClient.recognize(tenantCode, audio, formatOf(recordingUrl), 16000);
        } catch (Exception e) {
            log.warn("400 语音识别失败 tenant={} callId={} error={}", tenantCode, callId, e.getMessage());
            phoneCallService.markAsr(channel, callId, false, "", "转写失败：" + friendly(e));
            return;
        }
        if (!result.available()) {
            phoneCallService.markAsr(channel, callId, false, "",
                    result.hint().isBlank() ? "语音能力不可用" : result.hint());
            return;
        }
        if (result.text().isBlank()) {
            phoneCallService.markAsr(channel, callId, false, "",
                    "识别结果为空（可能是静音、噪声或非中文语音）");
            return;
        }
        phoneCallService.markAsr(channel, callId, true, result.text(), null);
    }

    /** 调 supportflow-ai 合成 IVR 播报音频（话务平台拿它直接播放，不用自己做 TTS） */
    public TtsResult synthesize(String tenantCode, String text, String voice, String format) {
        return speechAiClient.synthesize(tenantCode, text, voice, format);
    }

    /** 下载录音：按上限截断读取，避免几十分钟的整通录音把内存撑爆 */
    private byte[] download(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declared > maxAudioBytes) {
            throw new IllegalStateException("录音 " + (declared / 1024 / 1024) + "MB，超过上限 "
                    + (maxAudioBytes / 1024 / 1024) + "MB");
        }
        try (InputStream in = response.body()) {
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxAudioBytes) {
                    throw new IllegalStateException("录音超过上限 " + (maxAudioBytes / 1024 / 1024) + "MB");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /** 从 URL 后缀猜音频格式（话务侧常见 mp3 / wav / pcm），猜不出就交给平台默认 */
    private static String formatOf(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            return "";
        }
        String ext = lower.substring(dot + 1);
        int query = ext.indexOf('?');
        if (query >= 0) {
            ext = ext.substring(0, query);
        }
        return switch (ext) {
            case "wav", "pcm", "mp3", "amr", "opus", "speex" -> ext;
            default -> "";
        };
    }

    private static String safeUrl(String url) {
        return url != null && url.length() > 120 ? url.substring(0, 120) + "..." : url;
    }

    private static String friendly(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
