package io.supportflow.customer.dto.wechat;

/** 保存入参 */
public record SaveCommand(String appId, String appSecret, String token, String aesKey,
                          String welcomeText, String templateLogistics,
                          String templateTicket, String templateCoupon,
                          /** 小程序订阅消息模板 ID（工单进展 / 客服接入），公众号不用填 */
                          String subscribeTicket, String subscribeService,
                          /** 1-公众号 2-小程序（不传按公众号处理） */
                          Integer platform) {
}
