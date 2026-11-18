package io.supportflow.customer.nativechannel;

import io.supportflow.common.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 原生渠道出站客户端：给 App 推消息、让邮件网关发信。
 *
 * <p>两者都是「POST 一个 JSON 到企业自己的服务」，差别只在路径与载荷，所以合成一个客户端：
 * App 的推送服务负责下发 APNs / FCM（推送证书在企业侧，不该放到客服平台里）；
 * 邮件网关负责真正投递（平台的 SMTP 直连容易进垃圾箱，也让企业邮箱密码散落在多套系统）。
 * 请求都带 {@code X-SupportFlow-Timestamp} 与 {@code X-SupportFlow-Signature}，接收方可验签。</p>
 */
@Component
public class NativeOutboundClient {

    private static final Logger log = LoggerFactory.getLogger(NativeOutboundClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Transport transport;

    @Autowired
    public NativeOutboundClient() {
        this(new JdkTransport());
    }

    public NativeOutboundClient(Transport transport) {
        this.transport = transport;
    }

    /** 可替换的传输层：自检时塞假实现，就能离线验证「请求怎么拼」 */
    public interface Transport {
        String post(String url, Map<String, String> headers, String jsonBody);
    }

    /** App 离线推送：把坐席/机器人的回复交给企业的推送服务 */
    public boolean pushToApp(String webhook, String secret, String userId, String title, String body,
                             String sessionNo) {
        if (webhook == null || webhook.isBlank()) {
            log.debug("App 没配离线推送地址，跳过推送 userId={}", userId);
            return false;
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("userId", userId);
        payload.put("title", title == null ? "客服回复" : title);
        payload.put("body", body);
        if (sessionNo != null) {
            payload.put("sessionNo", sessionNo);
        }
        payload.put("bizType", "customer-service");
        try {
            String response = transport.post(webhook, signedHeaders(secret, payload.toString()), payload.toString());
            log.info("App 推送已发出 userId={} 响应={}", userId, preview(response));
            return true;
        } catch (Exception e) {
            log.warn("App 推送失败 userId={} error={}", userId, e.getMessage());
            return false;
        }
    }

    /** 邮件回信 / 自动回执：交给企业的邮件网关投递 */
    public boolean sendMail(String webhook, String secret, String to, String subject, String text,
                            String inReplyTo) {
        if (webhook == null || webhook.isBlank()) {
            log.debug("邮件渠道没配发信地址，跳过发信 to={}", to);
            return false;
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("to", to);
        payload.put("subject", subject == null ? "客服回复" : subject);
        payload.put("text", text == null ? "" : text);
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            // 带上 In-Reply-To，客户邮箱里会自动归到同一封会话里
            payload.put("inReplyTo", inReplyTo);
        }
        try {
            String response = transport.post(webhook, signedHeaders(secret, payload.toString()), payload.toString());
            log.info("邮件已交给网关投递 to={} 主题={} 响应={}", to, subject, preview(response));
            return true;
        } catch (Exception e) {
            log.warn("邮件发送失败 to={} error={}", to, e.getMessage());
            return false;
        }
    }

    private Map<String, String> signedHeaders(String secret, String body) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String signature = secret == null || secret.isBlank()
                ? "" : NativeSignature.sign(secret, timestamp, body);
        return Map.of(
                "Content-Type", "application/json; charset=utf-8",
                "X-SupportFlow-Timestamp", timestamp,
                "X-SupportFlow-Signature", signature);
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }

    /** 真实 HTTP 实现（自检替换它） */
    static class JdkTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        @Override
        public String post(String url, Map<String, String> headers, String jsonBody) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            headers.forEach(builder::header);
            try {
                return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
            } catch (Exception e) {
                throw new BizException(50002, "调用外部服务失败：" + e.getMessage());
            }
        }
    }
}
