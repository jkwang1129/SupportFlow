package io.supportflow.billing.dto;

/**
 * 平台「支付配置」页（商户号未掩码：只有平台账号能看）。
 */
public record PaymentConfigVO(Boolean onlinePayEnabled, String alipayMerchantId,
                              String wechatMerchantId, String corpAccountName,
                              String corpAccountNo, String corpBankName, Boolean configured) {
}
