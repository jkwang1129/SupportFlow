package io.supportflow.customer.wechat;

import io.supportflow.common.exception.BizException;
import io.supportflow.customer.dto.wechat.TokenResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 调微信开放接口的最小客户端：换 access_token、客服消息、模板消息（公众号）、
 * 订阅消息（小程序）、网页授权换 openid。
 *
 * <p>只做四件事，不引微信 SDK（见 {@link WeChatCrypt} 的说明）。HTTP 走一个可替换的
 * {@link Transport}：默认是 JDK 的 HttpClient，自检/单测可以塞假的实现，
 * 这样"请求怎么拼、错误码怎么翻译"这些逻辑可以离线验证，不需要真的连微信。</p>
 */
@Component
public class WeChatApiClient {

    private static final Logger log = LoggerFactory.getLogger(WeChatApiClient.class);
    /** 微信开放接口地址：做成可配置，自检可以指向本地假微信服务器，验证请求体与错误翻译 */
    private final String base;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Transport transport;

    @Autowired
    public WeChatApiClient(@Value("${supportflow.wechat.api-base:https://api.weixin.qq.com}") String apiBase) {
        this(new JdkTransport(), apiBase);
    }

    public WeChatApiClient(Transport transport) {
        this(transport, "https://api.weixin.qq.com");
    }

    public WeChatApiClient(Transport transport, String apiBase) {
        this.transport = transport;
        this.base = (apiBase == null || apiBase.isBlank())
                ? "https://api.weixin.qq.com" : apiBase.replaceAll("/+$", "");
    }

    /** 接口传输层：返回响应体字符串（GET / POST 两种） */
    public interface Transport {
        String get(String url);

        String post(String url, String jsonBody);
    }

    /** 换 access_token（有效期 7200 秒，调用方负责缓存） */
    public TokenResult accessToken(String appId, String appSecret) {
        String url = base + "/cgi-bin/token?grant_type=client_credential&appid=" + appId + "&secret=" + appSecret;
        JsonNode node = read(transport.get(url), "获取 access_token");
        return new TokenResult(node.path("access_token").asText(), node.path("expires_in").asInt(7200));
    }

    /** 客服消息：客户发消息后 48 小时内可以主动给他发文本（走这条推机器人/坐席回复） */
    public void sendCustomText(String accessToken, String openid, String content) {
        ObjectNode body = mapper.createObjectNode();
        body.put("touser", openid);
        body.put("msgtype", "text");
        body.putObject("text").put("content", content);
        JsonNode node = read(transport.post(base + "/cgi-bin/message/custom/send?access_token=" + accessToken,
                body.toString()), "发送客服消息");
        log.info("微信客服消息已发送 openid={} 长度={}", openid, content == null ? 0 : content.length());
        if (node.has("msgid")) {
            log.debug("微信客服消息 msgid={}", node.path("msgid").asText());
        }
    }

    /** 模板消息：物流提醒 / 工单进展 / 优惠券到账 */
    public String sendTemplate(String accessToken, ObjectNode payload) {
        JsonNode node = read(transport.post(base + "/cgi-bin/message/template/send?access_token=" + accessToken,
                payload.toString()), "发送模板消息");
        return node.path("msgid").asText("");
    }

    /**
     * 小程序订阅消息。
     *
     * <p>与公众号模板消息最大的差别：**用户点一次授权、平台只能发一次**（一次性订阅）。
     * 所以最常见的失败不是"代码写错了"，而是"客户没订阅 / 次数用完了"——
     * 这里把微信的错误码翻成人能看懂的话，并把它作为异常抛给上层去记日志
     * （日志里要能看出是"没订阅"还是"模板不对"，这两种要企业做的事完全不同）。</p>
     *
     * @param page      点击消息后打开的小程序页面（如 {@code pages/service/index}），可空
     * @param data      模板数据：{@code {"thing1":{"value":"..."}, ...}}
     */
    public void sendSubscribe(String accessToken, String openid, String templateId,
                              String page, ObjectNode data) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("touser", openid);
        payload.put("template_id", templateId);
        if (page != null && !page.isBlank()) {
            payload.put("page", page.trim());
        }
        // 小程序订阅消息里 data 是「字段名 → {value}」的平铺结构（和模板消息一致）
        payload.set("data", data == null ? mapper.createObjectNode() : data);
        JsonNode node = read(transport.post(
                base + "/cgi-bin/message/subscribe/send?access_token=" + accessToken,
                payload.toString()), "发送订阅消息");
        log.info("小程序订阅消息已发送 openid={} templateId={}", openid, templateId);
        if (node.has("msgid")) {
            log.debug("小程序订阅消息 msgid={}", node.path("msgid").asText());
        }
    }

    /** 订阅消息的常见错误码 → 人能看懂的话（自检会逐条断言） */
    public static String subscribeErrorHint(int errcode) {
        return switch (errcode) {
            case 43101 -> "用户没有订阅该消息，或订阅次数已用完（一次性订阅需要客户每次授权）";
            case 47003 -> "模板参数不合法（检查模板字段名与内容长度：thing 类最长 20 个字符）";
            case 40037 -> "模板 ID 不正确（小程序后台申请的订阅模板与公众号模板不是一套）";
            case 41030 -> "页面路径不存在（page 要写成 pages/xxx/index 这种，且已发布）";
            case 40003 -> "openid 不正确（小程序与公众号的 openid 不通用）";
            default -> null;
        };
    }

        /** 网页授权：code 换 openid */
    public String exchangeOpenId(String appId, String appSecret, String code) {
        String url = base + "/sns/oauth2/access_token?appid=" + appId + "&secret=" + appSecret
                + "&code=" + code + "&grant_type=authorization_code";
        JsonNode node = read(transport.get(url), "网页授权换取 openid");
        return node.path("openid").asText();
    }

    /** 统一解析：微信的错误体是 {"errcode":40013,"errmsg":"invalid appid"}，得翻译成人能看懂的 */
    private JsonNode read(String response, String action) {
        try {
            JsonNode node = mapper.readTree(response == null ? "" : response);
            int errcode = node.path("errcode").asInt(0);
            if (errcode != 0) {
                // 常见错误码补一句"该怎么办"：订阅消息最常见的是没订阅、模板不对，
                // 光看 errcode=43101 没人知道要做什么
                String hint = subscribeErrorHint(errcode);
                throw new WeChatApiException(errcode, "微信接口报错（" + action + "）：errcode=" + errcode
                        + " errmsg=" + node.path("errmsg").asText()
                        + (hint == null ? "" : "；" + hint));
            }
            return node;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(50002, "微信接口返回无法解析（" + action + "）："
                    + String.valueOf(response).substring(0, Math.min(120, String.valueOf(response).length())));
        }
    }

    /** 真实的 HTTP 实现（自检里用假实现替换它） */
    static class JdkTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        @Override
        public String get(String url) {
            return send(HttpRequest.newBuilder(URI.create(url)).GET().build());
        }

        @Override
        public String post(String url, String jsonBody) {
            return send(HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build());
        }

        private String send(HttpRequest request) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return response.body();
            } catch (Exception e) {
                throw new BizException(50002, "调用微信接口失败：" + e.getMessage());
            }
        }
    }

    }
