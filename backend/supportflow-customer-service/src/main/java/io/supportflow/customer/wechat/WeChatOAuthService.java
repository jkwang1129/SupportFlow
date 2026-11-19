package io.supportflow.customer.wechat;

import io.supportflow.common.context.TenantContext;

import io.supportflow.common.exception.BizException;
import io.supportflow.customer.entity.ChannelKey;
import io.supportflow.customer.entity.WeChatChannelConfig;
import io.supportflow.customer.entity.WeChatCustomerBind;
import io.supportflow.customer.mapper.ChannelKeyMapper;
import io.supportflow.customer.security.VisitorTokenService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 微信公众号网页授权（拿 openid，把微信身份接到客户档案上）。
 *
 * <p>链路：菜单跳 /oauth → 302 到微信授权页 → 微信带 code 回 /oauth/callback →
 * code 换 openid → 绑定客户 → 带访客身份令牌跳访客页
 * （这样网页会话和公众号消息是同一个人）。scope 用 snsapi_base：静默授权、只需要 openid。</p>
 */
@Service
public class WeChatOAuthService {

    private static final Logger log = LoggerFactory.getLogger(WeChatOAuthService.class);

    private final WeChatConfigService configService;
    private final WeChatInboundService inboundService;
    private final WeChatApiClient apiClient;
    private final ChannelKeyMapper channelKeyMapper;
    private final VisitorTokenService visitorTokenService;

    /** 访客页地址（公众号菜单最终落到这里；不配就用相对路径） */
    @Value("${supportflow.wechat.visitor-base-url:}")
    private String visitorBaseUrl;

    public WeChatOAuthService(WeChatConfigService configService, WeChatInboundService inboundService,
                              WeChatApiClient apiClient, ChannelKeyMapper channelKeyMapper,
                              VisitorTokenService visitorTokenService) {
        this.configService = configService;
        this.inboundService = inboundService;
        this.apiClient = apiClient;
        this.channelKeyMapper = channelKeyMapper;
        this.visitorTokenService = visitorTokenService;
    }

    /** 网页授权第一步：拼接微信授权地址。 */
    public String authorizeUrl(String appId, String redirect) {
        WeChatChannelConfig config = configService.byAppId(appId);
        if (config == null) {
            throw new BizException(40401, "公众号渠道还没配置或已停用");
        }
        String callback = configService.oauthCallbackUrl(appId)
                + (redirect == null || redirect.isBlank() ? "" : "?redirect=" + url(redirect));
        return "https://open.weixin.qq.com/connect/oauth2/authorize?appid=" + appId
                + "&redirect_uri=" + url(callback)
                + "&response_type=code&scope=snsapi_base"
                + "&state=" + url(redirect == null ? "" : redirect)
                + "#wechat_redirect";
    }

    /** 网页授权回调：拿 code 换 openid，再把用户带回原来的页面。 */
    public String callbackTarget(String appId, String code, String state) {
        WeChatChannelConfig config = configService.byAppId(appId);
        if (config == null) {
            throw new BizException(40401, "公众号渠道还没配置或已停用");
        }
        if (code == null || code.isBlank()) {
            throw new BizException(40001, "缺少微信授权 code");
        }
        return TenantContext.bind(config.getTenantCode(), () -> {
        String openid = apiClient.exchangeOpenId(appId, config.getAppSecret(), code);
        if (openid == null || openid.isBlank()) {
            throw new BizException(50002, "微信没有返回 openid，请检查网页授权域名配置");
        }
        WeChatCustomerBind bind = inboundService.bindOrCreate(config, openid, false);
        String identityToken = visitorTokenService.createIdentityToken(
                bind.getCustomerId(), bind.getCustomerNo(), bind.getCustomerName(), config.getTenantCode());
        String appKey = appKeyOf(config.getChannelId());
        String base = visitorBaseUrl == null ? "" : visitorBaseUrl.replaceAll("/+$", "");
        String target = (state == null || state.isBlank())
                ? base + "/visitor?appId=" + appKey + "&wt=" + url(identityToken)
                : state + (state.contains("?") ? "&" : "?") + "wt=" + url(identityToken);
        log.info("公众号网页授权完成 appId={} openid={} customerNo={}", appId, openid, bind.getCustomerNo());
        return target;
        });
    }

    /** 取渠道当前生效的密钥（appKey），没有可用密钥就抛业务异常。 */
    private String appKeyOf(Long channelId) {
        ChannelKey key = channelKeyMapper.selectOne(Wrappers.<ChannelKey>lambdaQuery()
                .eq(ChannelKey::getChannelId, channelId)
                .eq(ChannelKey::getStatus, 1)
                .eq(ChannelKey::getDeleted, false)
                .orderByDesc(ChannelKey::getId)
                .last("LIMIT 1"));
        if (key == null) {
            throw new BizException(40401, "这个公众号渠道还没有可用密钥");
        }
        return key.getAppKey();
    }

    private static String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
