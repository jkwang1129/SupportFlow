package io.supportflow.customer.nativechannel;

import io.supportflow.common.util.Texts;
import io.supportflow.customer.dto.channel.MailSmtpSettings;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

/**
 * 邮件工单通道 · 出站：坐席回复 / 受理回执 / 结单回访都用 SMTP 直接发信。
 *
 * <p>发信参数（服务器 / 端口 / 账号 / 授权码 / 发件人）**按渠道取**：同一套部署上跑多个租户，
 * 每个租户用自己客服邮箱发信，所以不能是全局一份配置。</p>
 *
 * <p>三条约定：</p>
 * <ol>
 *   <li><b>同主题回信</b>：主题形如 {@code Re: 原主题}，让客户邮箱里的会话保持一条线程；</li>
 *   <li><b>正文带会话号</b>：客户后续回复时我们能对回同一会话（也方便人工核对）；</li>
 *   <li><b>没配就不发</b>：host/username/password 任一为空时返回 false，由调用方决定是否降级。</li>
 * </ol>
 */
@Component
public class MailOutboundSender {

    private static final Logger log = LoggerFactory.getLogger(MailOutboundSender.class);

        /**
     * 取这条渠道的发信 SMTP 参数：直接读渠道配置（IMAP / SMTP 都在渠道上，按租户隔离）。
     * 没配全就返回 null，由调用方决定"不外发"还是"走历史网关兼容路径"。
     */
    public MailSmtpSettings resolve(Map<String, Object> config) {
        if (config == null) {
            return null;
        }
        String host = str(config.get("smtpHost"));
        String username = str(config.get("smtpUsername"));
        String password = str(config.get("smtpPassword"));
        if (!notBlank(host) || !notBlank(username) || !notBlank(password)) {
            return null;
        }
        String fromName = notBlank(str(config.get("smtpFromName")))
                ? str(config.get("smtpFromName")) : "SupportFlow 智能客服";
        return new MailSmtpSettings(host, intOf(config.get("smtpPort"), 465), username, password, fromName);
    }

    /**
     * 发一封客服回复。
     *
     * @param smtp      发信参数（由 {@link #resolve} 按渠道解析）
     * @param to        客户邮箱（来自 channel_identity.external_id）
     * @param subject   原始主题（会自动加 Re: 前缀；已是 Re: 则不重复加）
     * @param content   坐席回复正文
     * @param sessionNo 会话号，附在正文末尾便于追踪与后续对回
     * @return 是否发送成功（失败只记日志，不影响坐席那边"消息已发出"的事实）
     */
    public boolean sendReply(MailSmtpSettings smtp, String to, String subject, String content, String sessionNo) {
        String body = (content == null ? "" : content)
                + "\n\n---\n会话号：" + (sessionNo == null ? "-" : sessionNo)
                + "\n（直接回复本邮件即可继续沟通）";
        return deliver(smtp, to, replySubject(subject), body, null, sessionNo);
    }

    /**
     * 发一封普通文本邮件（自动受理回执走这里）。
     *
     * @param inReplyTo 原始 Message-ID：带上它客户的邮箱会把回执归到同一线程
     */
    public boolean sendText(MailSmtpSettings smtp, String to, String subject, String text, String inReplyTo) {
        return deliver(smtp, to, subject, text, inReplyTo, null);
    }

    private boolean deliver(MailSmtpSettings smtp, String to, String subject, String body,
                            String inReplyTo, String sessionNo) {
        if (smtp == null || to == null || to.isBlank()) {
            return false;
        }
        try {
            Session session = buildSession(smtp);
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(smtp.username(), smtp.fromName(), StandardCharsets.UTF_8.name()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
            message.setSubject(subject == null || subject.isBlank() ? "客服回复" : subject,
                    StandardCharsets.UTF_8.name());
            message.setContent(alternative(smtp.fromName(), body == null ? "" : body));
            if (inReplyTo != null && !inReplyTo.isBlank()) {
                message.setHeader("In-Reply-To", inReplyTo);
                message.setHeader("References", inReplyTo);
            }
            if (sessionNo != null) {
                message.setHeader("X-SupportFlow-Session", sessionNo);
            }
            // 防止"自己收自己的信"：平台发出的邮件带上标准自动回复标记，
            // 轮询器会跳过带这个标记的邮件。本地演示用同一个邮箱既当客户又当客服邮箱时，
            // 没有它就会形成"回执 → 被当新来信 → 再回执"的无限循环。
            message.setHeader("Auto-Submitted", "auto-replied");
            message.setHeader("X-SupportFlow-Mail", "outbound");
            try (Transport transport = session.getTransport("smtps")) {
                transport.connect(smtp.host(), smtp.port(), smtp.username(), smtp.password());
                transport.sendMessage(message, message.getAllRecipients());
            }
            log.info("邮件已发送 to={} subject={} sessionNo={}", mask(to), subject, sessionNo);
            return true;
        } catch (Exception e) {
            // 发信失败不改业务状态：消息已经落库、坐席那边也看得到，运营可以手工补一封
            log.warn("邮件发送失败 to={} subject={} error={}", mask(to), subject, e.getMessage());
            return false;
        }
    }

    private Session buildSession(MailSmtpSettings smtp) {
        Properties props = new Properties();
        props.put("mail.transport.protocol", "smtps");
        props.put("mail.smtps.host", smtp.host());
        props.put("mail.smtps.port", String.valueOf(smtp.port()));
        props.put("mail.smtps.auth", "true");
        props.put("mail.smtps.ssl.enable", "true");
        props.put("mail.smtps.connectiontimeout", "8000");
        props.put("mail.smtps.timeout", "10000");
        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(smtp.username(), smtp.password());
            }
        });
    }

    /**
     * 邮件正文：纯文本 + 品牌 HTML 两个版本（multipart/alternative）。
     *
     * <p>为什么要 HTML：纯文本邮件在客户眼里就是"系统随便发的一条通知"，而这是企业对外
     * 的第一印象。HTML 版带上品牌头、正文与页脚，和产品里的效果一致；纯文本版本留作
     * 不支持 HTML 的客户端的兜底。</p>
     */
    private static MimeMultipart alternative(String fromName, String body) throws Exception {
        MimeBodyPart text = new MimeBodyPart();
        text.setText(body, StandardCharsets.UTF_8.name());

        MimeBodyPart html = new MimeBodyPart();
        html.setContent(page(fromName, body), "text/html;charset=UTF-8");

        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(text);
        alternative.addBodyPart(html);
        return alternative;
    }

    /** 品牌 HTML 版式：邮件客户端大多不认外部样式，全部写内联样式；换行转 br、链接自动可点 */
    private static String page(String fromName, String body) {
        String brand = Texts.htmlEscape(fromName == null || fromName.isBlank() ? "SupportFlow 智能客服" : fromName);
        String content = linkify(Texts.htmlEscape(body)).replace("\n", "<br />");
        return "<div style=\"margin:0;padding:24px 12px;background:#f1f5f9;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;\">"
                + "<div style=\"max-width:600px;margin:0 auto;background:#ffffff;border:1px solid #e6e8ee;"
                + "border-radius:14px;overflow:hidden;\">"
                + "<div style=\"background:linear-gradient(135deg,#2563eb,#1d4ed8);padding:20px 26px;color:#ffffff;\">"
                + "<div style=\"font-size:17px;font-weight:700;\">" + brand + "</div>"
                + "<div style=\"font-size:12px;opacity:.85;margin-top:4px;\">SupportFlow 智能客服 · 邮件工单</div>"
                + "</div>"
                + "<div style=\"padding:26px;color:#1f2430;font-size:14px;line-height:1.9;\">" + content + "</div>"
                + "<div style=\"padding:14px 26px;background:#f7f8fa;border-top:1px solid #eef0f4;"
                + "color:#8a8fa3;font-size:12px;line-height:1.7;\">"
                + "本邮件由SupportFlow 智能客服系统自动发送，直接回复本邮件即可继续沟通。"
                + "</div>"
                + "</div></div>";
    }

        /** 把正文里的 http(s) 链接变成可点按钮（回访邮件的评价链接靠它，用蓝色高亮） */
    private static String linkify(String escaped) {
        return escaped.replaceAll("(https?://[^\\s<]+)",
                "<a href=\"$1\" style=\"color:#2563eb;text-decoration:underline;word-break:break-all;\">$1</a>");
    }

    /** Re: 前缀：已经带过就不重复加（避免出现 Re: Re: Re:） */
    static String replySubject(String subject) {
        String text = subject == null || subject.isBlank() ? "客服回复" : subject.trim();
        return text.toLowerCase().startsWith("re:") ? text : "Re: " + text;
    }

    /** 日志里不打印完整邮箱（合规：日志不该成为个人信息泄露点） */
    private static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intOf(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
