package io.supportflow.customer.wechat;

import io.supportflow.common.exception.BizException;
import io.supportflow.customer.entity.WeChatChannelConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 微信小程序「客服消息」回调的业务：验签、安全模式解密、转发给进站处理。
 *
 * <p>客户在小程序里点「在线客服」（{@code <button open-type="contact">}）进入微信原生客服会话，
 * 微信把消息推到小程序后台配置的这个地址——<b>不需要 web-view、不需要 H5</b>，
 * 回复走同一个客服消息接口（{@code cgi-bin/message/custom/send}），客户就在微信里收到。</p>
 *
 * <p>与公众号的两个差别：报文是 <b>JSON</b>（不是 XML）；事件里有 {@code user_enter_tempsession}
 * （客户进入客服会话），要当成"欢迎语时机"。</p>
 */
@Service
public class MiniAppCallbackService {

    private static final Logger log = LoggerFactory.getLogger(MiniAppCallbackService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WeChatConfigService configService;
    private final WeChatInboundService inboundService;

    public MiniAppCallbackService(WeChatConfigService configService, WeChatInboundService inboundService) {
        this.configService = configService;
        this.inboundService = inboundService;
    }

    /** 小程序后台「消息推送」保存时来验签，通过就把 echostr 原样返回。 */
    public String verifyEcho(String appId, String signature, String timestamp,
                             String nonce, String echostr) {
        WeChatChannelConfig config = requireMiniApp(appId);
        boolean ok = WeChatCrypt.checkSignature(config.getToken(), signature, timestamp, nonce);
        log.info("小程序消息推送校验 appId={} 结果={}", appId, ok ? "通过" : "失败");
        return ok ? echostr : "invalid";
    }

    /** 客服消息 / 事件推送（JSON；安全模式时 body 是 {"Encrypt":"…","ToUserName":"…"}）。 */
    public String receive(String appId, String body, String msgSignature,
                          String timestamp, String nonce) {
        WeChatChannelConfig config = requireMiniApp(appId);
        try {
            String plain = plainBody(config, body, msgSignature, timestamp, nonce);
            return inboundService.handleJson(appId, plain, false);
        } catch (Exception e) {
            log.warn("小程序客服消息处理失败 appId={} error={}", appId, e.getMessage());
            return "success";
        }
    }

    private WeChatChannelConfig requireMiniApp(String appId) {
        WeChatChannelConfig config = configService.byAppId(appId);
        if (config == null) {
            throw new BizException(40401, "小程序渠道还没配置或已停用");
        }
        if (config.getPlatform() != null && config.getPlatform() == 2) {
            return config;
        }
        // 平台字段还没填时也放行：老配置升级上来不能直接不可用，只在日志里提示
        log.warn("AppID {} 的配置 platform 不是「小程序」，请在小程序渠道配置里核对", appId);
        return config;
    }

    private String plainBody(WeChatChannelConfig config, String body, String msgSignature,
                             String timestamp, String nonce) {
        if (body == null || body.isBlank()) {
            return "{}";
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new BizException(40001, "小程序消息不是合法 JSON");
        }
        if (!node.has("Encrypt")) {
            if (!WeChatCrypt.checkSignature(config.getToken(), null, timestamp, nonce)) {
                log.debug("小程序明文消息未带 signature，按配置放行 appId={}", config.getAppId());
            }
            return body;
        }
        String encrypt = node.path("Encrypt").asText();
        if (!WeChatCrypt.msgSignatureMatches(config.getToken(), timestamp, nonce, encrypt, msgSignature)) {
            throw new BizException(40301, "小程序消息签名校验失败");
        }
        // 安全模式的明文是 XML（微信加密后统一按公众号那套格式封装）
        String plain = WeChatCrypt.decrypt(config.getAesKey(), encrypt, config.getAppId());
        if (plain.trim().startsWith("<")) {
            Map<String, String> map = WeChatXml.parse(plain);
            return MAPPER.valueToTree(map).toString();
        }
        return plain;
    }

}
