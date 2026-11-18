package io.supportflow.customer.dto.channel;

/** 邮件进线载荷 */
public record MailMessage(String from, String fromName, String subject, String text, String messageId) {
}
