package io.supportflow.customer.controller.wechat;

import io.supportflow.common.exception.BizException;
import io.supportflow.customer.entity.WeChatChannelConfig;
import io.supportflow.customer.wechat.WeChatConfigService;
import io.supportflow.customer.wechat.WeChatCrypt;
import io.supportflow.customer.wechat.WeChatInboundService;
import io.supportflow.customer.wechat.WeChatXml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 微信公众号服务器回调（免登录，微信直接访问）。
 * GET：公众号后台点"保存"时微信来验签，必须原样回 echostr；
 * POST：客户消息 / 事件推送，验签（安全模式还要解密）后交给 WeChatInboundService。
 * 一定要在 5 秒内返回 "success"，否则微信会重推同一条消息。
 */
@RestController
@RequestMapping("/api/customer/wechat/{appId}")
public class WeChatCallbackController {

    private static final Logger log = LoggerFactory.getLogger(WeChatCallbackController.class);

    private final WeChatConfigService configService;
    private final WeChatInboundService inboundService;

    public WeChatCallbackController(WeChatConfigService configService, WeChatInboundService inboundService) {
        this.configService = configService;
        this.inboundService = inboundService;
    }

    /**
     * 微信服务器地址校验（GET）：按要求回显 {@code echostr}。
     *
     * <p>公众号后台"服务器配置"点保存时微信会先打这个口，过不了就不让保存。</p>
     */
    @GetMapping("/callback")
    public String verify(@PathVariable String appId,
                         @RequestParam(required = false) String signature,
                         @RequestParam(required = false) String timestamp,
                         @RequestParam(required = false) String nonce,
                         @RequestParam(required = false) String echostr) {
        WeChatChannelConfig config = configService.byAppId(appId);
        if (config == null) {
            log.warn("公众号回调：AppID 未配置 appId={}", appId);
            return "invalid";
        }
        boolean ok = WeChatCrypt.checkSignature(config.getToken(), signature, timestamp, nonce);
        log.info("公众号服务器校验 appId={} 结果={}", appId, ok ? "通过" : "失败");
        return ok ? echostr : "invalid";
    }

    /**
     * 接收微信推送的消息（POST）。
     *
     * <p>微信只等 5 秒，超时会重推，所以这里只做"落库 + 触发"并立刻返回 success，
     * 真正的回复走客服消息接口异步推。</p>
     */
    @PostMapping("/callback")
    public String receive(@PathVariable String appId,
                          @RequestParam(required = false) String signature,
                          @RequestParam(required = false) String msg_signature,
                          @RequestParam(required = false) String timestamp,
                          @RequestParam(required = false) String nonce,
                          @RequestBody(required = false) String body) {
        WeChatChannelConfig config = configService.byAppId(appId);
        if (config == null) {
            log.warn("公众号回调：AppID 未配置 appId={}", appId);
            return "success";
        }
        try {
            String plain = plainBody(config, body, msg_signature, signature, timestamp, nonce);
            return inboundService.handle(appId, plain, false);
        } catch (Exception e) {
            // 回调里出任何异常都返回 success：避免微信重试风暴，具体原因看日志
            log.warn("公众号消息处理失败 appId={} error={}", appId, e.getMessage());
            return "success";
        }
    }

    // 明文模式直接用 body；安全模式先验 msg_signature（签名里要多带一段密文）再 AES 解密
    private String plainBody(WeChatChannelConfig config, String body, String msgSignature,
                             String signature, String timestamp, String nonce) {
        if (body == null || body.isBlank()) {
            return "";
        }
        if (!body.contains("<Encrypt>")) {
            if (!WeChatCrypt.checkSignature(config.getToken(), signature, timestamp, nonce)) {
                log.warn("公众号明文消息验签失败 appId={}", config.getAppId());
            }
            return body;
        }
        Map<String, String> wrapper = WeChatXml.parse(body);
        String encrypt = wrapper.get("Encrypt");
        if (encrypt == null || encrypt.isBlank()) {
            throw new BizException(40001, "安全模式下没有加密内容");
        }
        if (!WeChatCrypt.msgSignatureMatches(config.getToken(), timestamp, nonce, encrypt, msgSignature)) {
            throw new BizException(40301, "微信消息签名校验失败");
        }
        return WeChatCrypt.decrypt(config.getAesKey(), encrypt, config.getAppId());
    }
}
