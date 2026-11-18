package io.supportflow.customer.dto.channel;

/**
 * 联调模拟器的一步：这一步做了什么 + 真实链路的返回。
 *
 * @param result 就是对应渠道进线接口的回执（App 是 {@link AppInboundResultVO}、
 *               邮件是 {@link MailInboundResultVO}、热线是 {@link PhoneEventResultVO}），
 *               所以这里用 Object 承载而不是再包一层。
 */
public record SimulateStepVO(String name, Object result) {
}
