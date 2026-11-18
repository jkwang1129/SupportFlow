package io.supportflow.billing.dto;

/** 支付配置入参 */
public record PaymentConfigBody(Boolean onlinePayEnabled, String alipayMerchantId,
                                String wechatMerchantId, String corpAccountName,
                                String corpAccountNo, String corpBankName) {
}
