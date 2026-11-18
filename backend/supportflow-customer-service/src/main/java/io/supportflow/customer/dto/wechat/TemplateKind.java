package io.supportflow.customer.dto.wechat;

/**
 * 三类公众号模板消息。
 *
 * <p>只声明"有哪几类"；"每类对应配置里的哪个模板 ID"是业务映射，
 * 放在 {@code WeChatNotifyService} 里——dto 不该反过来依赖渠道配置实体。</p>
 */
public enum TemplateKind {
    /** 物流进展 */
    LOGISTICS,
    /** 工单进展 */
    TICKET,
    /** 优惠券到账 */
    COUPON
}
