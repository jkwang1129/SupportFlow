package io.supportflow.customer.controller.wechat;

import io.supportflow.customer.wechat.WeChatOAuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信公众号网页授权（拿 openid，把微信身份接到客户档案上）。
 *
 * <p>授权地址拼接、code 换 openid、绑定与令牌签发都在 {@link WeChatOAuthService} 里，
 * 控制器只负责把 service 返回的地址转成 302。</p>
 */
@RestController
@RequestMapping("/api/customer/wechat/{appId}")
public class WeChatOAuthController {

    private final WeChatOAuthService oauthService;

    public WeChatOAuthController(WeChatOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    /**
     * 网页授权第一步：拼接微信授权地址并 302 过去。
     */
    @GetMapping("/oauth")
    public String authorize(@PathVariable String appId,
                            @RequestParam(required = false) String redirect) {
        return "redirect:" + oauthService.authorizeUrl(appId, redirect);
    }

    /**
     * 网页授权回调：拿 code 换 openid，再把用户带回原来的页面。
     */
    @GetMapping("/oauth/callback")
    public String callback(@PathVariable String appId,
                           @RequestParam(required = false) String code,
                           @RequestParam(required = false) String state) {
        return "redirect:" + oauthService.callbackTarget(appId, code, state);
    }
}
