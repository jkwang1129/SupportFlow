package io.supportflow.customer.dto.wechat;

/** 模板消息测试入参：模板类型（TICKET / LOGISTICS / COUPON）。 */
public record TestTemplateBody(String kind) {
}
