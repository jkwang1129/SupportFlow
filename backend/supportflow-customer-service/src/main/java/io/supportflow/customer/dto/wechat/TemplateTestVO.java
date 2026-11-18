package io.supportflow.customer.dto.wechat;

/**
 * 发一条模板消息测试的结果。
 */
public record TemplateTestVO(boolean sent, String openid, String kind) {
}
