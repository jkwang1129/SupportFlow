package io.supportflow.billing.dto;

/**
 * 下单 / 支付入参。
 *
 * @param payMethod 支付方式：1-支付宝、2-微信、3-对公转账；不传按对公转账处理
 * @param payNo     三方支付流水号（真实网关回调里带的）；不传由平台生成收款单号
 */
public record OrderBody(long planId, int cycle, String tenantCode, Integer payMethod, String payNo) {
}
