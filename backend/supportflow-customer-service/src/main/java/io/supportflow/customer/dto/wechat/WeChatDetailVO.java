package io.supportflow.customer.dto.wechat;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 公众号渠道详情：配置回显 + 回调地址 + 各模板要填的字段。
 *
 * <p>密钥类字段只回显"是否已配置"，不回显明文：管理端页面被截图/录屏的概率比想象中高。</p>
 *
 * @param templateFields 模板类型码 → 该模板要填的字段（LOGISTICS / TICKET / COUPON 三个固定键，
 *                       值是微信侧要求的字段清单），键名要与微信约定一致，所以保持 Map
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WeChatDetailVO(boolean configured, String appId, Boolean appSecretConfigured,
                             Boolean tokenConfigured, Boolean aesKeyConfigured,
                             String welcomeText, String templateLogistics, String templateTicket,
                             String templateCoupon, String subscribeTicket,
                             String subscribeService, Integer platform, String callbackUrl,
                             String oauthUrl, Map<String, List<String>> templateFields) {
}
