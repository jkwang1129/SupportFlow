package io.supportflow.customer.controller.wechat;

import io.supportflow.customer.wechat.MiniAppCallbackService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信小程序「客服消息」回调（免登录，微信直接访问）。
 *
 * <p>验签、安全模式解密与进站分发都在 {@link MiniAppCallbackService} 里，
 * 控制器只接参数、把它返回的字符串原样回给微信。</p>
 */
@RestController
@RequestMapping("/api/customer/wechat/{appId}")
public class MiniAppCallbackController {

    private final MiniAppCallbackService callbackService;

    public MiniAppCallbackController(MiniAppCallbackService callbackService) {
        this.callbackService = callbackService;
    }

    /**
     * 小程序后台「消息推送」保存时来验签，通过就把 echostr 原样返回
     */
    @GetMapping("/miniapp")
    public String verify(@PathVariable String appId,
                         @RequestParam(required = false) String signature,
                         @RequestParam(required = false) String timestamp,
                         @RequestParam(required = false) String nonce,
                         @RequestParam(required = false) String echostr) {
        return callbackService.verifyEcho(appId, signature, timestamp, nonce, echostr);
    }

    /**
     * 客服消息 / 事件推送（JSON；安全模式时 body 是 {"Encrypt":"…","ToUserName":"…"}）
     */
    @PostMapping("/miniapp")
    public String receive(@PathVariable String appId,
                          @RequestParam(required = false) String signature,
                          @RequestParam(required = false) String msg_signature,
                          @RequestParam(required = false) String timestamp,
                          @RequestParam(required = false) String nonce,
                          @RequestBody(required = false) String body) {
        return callbackService.receive(appId, body, msg_signature, timestamp, nonce);
    }
}
