package io.supportflow.customer.dto.channel;

/**
 * 一套发信参数（方案 A 的 SMTP）。
 *
 * <p>为什么做成"每次解析出来一份"而不是 Bean 里的固定字段：同一套部署上跑着多个租户，
 * 每个租户用**自己的**客服邮箱发信（发件人也是他们自己的地址），所以发信参数必须按渠道取，
 * 不能是全局的一份。</p>
 */
public record MailSmtpSettings(String host, int port, String username, String password, String fromName) {
}
