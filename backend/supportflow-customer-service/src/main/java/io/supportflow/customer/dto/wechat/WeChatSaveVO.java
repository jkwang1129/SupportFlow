package io.supportflow.customer.dto.wechat;

/**
 * 保存公众号渠道配置后的回执（把微信侧要填的地址一并回给页面）。
 */
public record WeChatSaveVO(boolean configured, String appId, String callbackUrl, String oauthUrl) {
}
