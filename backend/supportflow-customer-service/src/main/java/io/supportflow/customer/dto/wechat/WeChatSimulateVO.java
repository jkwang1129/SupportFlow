package io.supportflow.customer.dto.wechat;

/**
 * 公众号联调模拟器的结果（模拟 openid 不会真发到微信）。
 */
public record WeChatSimulateVO(String sessionNo, String customerNo, String openid, String note) {
}
