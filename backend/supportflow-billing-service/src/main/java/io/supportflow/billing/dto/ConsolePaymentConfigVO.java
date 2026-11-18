package io.supportflow.billing.dto;

/**
 * 收银台读的支付配置（登录即可读）。
 *
 * <p>与平台侧那份的区别：商户号**掩码**、多返两个"这个渠道现在能不能用"的开关；
 * 对公账户给全（客户要照着它转账）。</p>
 */
public record ConsolePaymentConfigVO(Boolean alipayEnabled, Boolean wechatEnabled,
                                     Boolean onlinePayEnabled, String alipayMerchantId,
                                     String wechatMerchantId, String corpAccountName,
                                     String corpAccountNo, String corpBankName,
                                     Boolean corpEnabled, Boolean configured) {
}
