package io.supportflow.customer.nativechannel;

import io.supportflow.customer.dto.channel.MailInbound;
import io.supportflow.customer.dto.channel.MailInboundResultVO;
import io.supportflow.customer.entity.Channel;
import io.supportflow.customer.mapper.ChannelMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 邮件工单通道 · 入站（方案 A）：按**渠道**定时用 IMAP 拉未读邮件，转成会话 / 工单。
 *
 * <p>关键点是"按渠道、也就是按租户"：以前这里读的是部署环境变量里的一份全局邮箱，
 * 多租户部署下会把所有企业的信都记到第一个邮件渠道上。现在改成——</p>
 * <ol>
 *   <li>遍历所有**启用中**的邮件渠道（channel_type=6，每个渠道属于一个租户）；</li>
 *   <li>IMAP 服务器 / 账号 / 授权码 / 文件夹 / 收件人过滤都从**这条渠道自己的配置**里读，
 *       所以 A 企业的邮箱信件只会进 A 企业，互不串台；</li>
 *   <li>没配 IMAP 的渠道直接跳过——邮件渠道只走这一条收信路径。</li>
 * </ol>
 *
 * <p>这一层只做"取信 + 解析"，业务动作全部交给已有的
 * {@link ChannelNativeInboundService#mailInbound}（绑客户、开会话、建工单、发回执、去重都在那边）。</p>
 *
 * <p>三条安全约定：只标已读、不删原件；按 {@code Message-ID} 去重（数据库里还有一层）；
 * 密码只存在渠道配置里、日志不打印账号密码。某个租户的邮箱连不上只影响它自己，不会中断其它租户。</p>
 */
@Component
public class MailInboundPoller {

    private static final Logger log = LoggerFactory.getLogger(MailInboundPoller.class);

    /** 渠道类型：6-邮件（与 channel_native_config / webhook 路由对齐） */
    private static final int CHANNEL_TYPE_MAIL = 6;
    /** 渠道状态：1-启用 */
    private static final int CHANNEL_ENABLED = 1;
    private static final String DEFAULT_FOLDER = "INBOX";
    /** 每轮最多往回扫多少封（从最新往前），用来替代不可靠的服务端 SEARCH */
    private static final int SCAN_LIMIT = 200;

    /**
     * 机器类发件人（本地部分命中即判定不是真人来信）。
     *
     * <p>客户邮箱里混进系统通知、退信、营销群发是常态：阿里云的产品动态、Facebook 的
     * "你有 N 条通知"、postmaster 的退信……这些如果不拦，会被当成新客户建成会话 / 工单，
     * 直接堆到坐席的「待接单」里（本方法就是为这类脏数据兜底）。</p>
     */
    private static final Set<String> MACHINE_SENDER_LOCALS = Set.of(
            "postmaster", "mailer-daemon", "mailerdaemon", "mail-daemon",
            "no-reply", "noreply", "no_reply", "donotreply", "do-not-reply", "do_not_reply",
            "bounce", "bounces",
            "notification", "notifications",
            "newsletter", "news", "marketing", "promotion", "promotions",
            "system", "auto-reply", "autoreply", "autoresponder");

    private final ChannelNativeInboundService inboundService;
    private final ChannelNativeConfigService configService;
    private final ChannelMapper channelMapper;
    private final int maxPerRound;

    /** 每个渠道上一次报过的错：同样的错不每轮刷屏（改配置后重新报） */
    private final Map<Long, String> lastErrorByChannel = new ConcurrentHashMap<>();

    public MailInboundPoller(ChannelNativeInboundService inboundService,
                             ChannelNativeConfigService configService,
                             ChannelMapper channelMapper,
                             @Value("${supportflow.mail.inbound.max-per-round:20}") int maxPerRound) {
        this.inboundService = inboundService;
        this.configService = configService;
        this.channelMapper = channelMapper;
        this.maxPerRound = Math.max(1, maxPerRound);
    }

    @Scheduled(fixedDelayString = "${supportflow.mail.inbound.interval-ms:60000}", initialDelayString = "20000")
    public void poll() {
        List<Channel> channels = enabledMailChannels();
        if (channels.isEmpty()) {
            return;
        }
        for (Channel channel : channels) {
            try {
                pollChannel(channel);
                lastErrorByChannel.remove(channel.getId());
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                // 同一个渠道同样的错只报一次：配置错了要人看日志，不是被淹没
                if (!message.equals(lastErrorByChannel.get(channel.getId()))) {
                    lastErrorByChannel.put(channel.getId(), message);
                    log.warn("邮件入站轮询失败 tenant={} channelId={} name={}：{}",
                            channel.getTenantCode(), channel.getId(), channel.getName(), message);
                }
            }
        }
    }

    /** 所有启用中的邮件渠道（每个渠道属于一个租户，配置各自独立） */
    private List<Channel> enabledMailChannels() {
        return channelMapper.selectList(Wrappers.<Channel>lambdaQuery()
                .eq(Channel::getChannelType, CHANNEL_TYPE_MAIL)
                .eq(Channel::getStatus, CHANNEL_ENABLED)
                .eq(Channel::getDeleted, false)
                .orderByAsc(Channel::getId));
    }

    /** 拉某个渠道自己的邮箱；没配 IMAP（或没配全）就直接跳过 */
    private void pollChannel(Channel channel) throws Exception {
        Map<String, Object> config = configService.raw(channel.getTenantCode(), channel.getId());
        String host = str(config.get("imapHost"));
        String username = str(config.get("imapUsername"));
        String password = str(config.get("imapPassword"));
        if (host.isBlank() || username.isBlank() || password.isBlank()) {
            // 没配邮箱的渠道（比如还没上线 / 历史遗留）直接跳过，不刷日志
            return;
        }
        int port = intOf(config.get("imapPort"), 993);
        String folderName = str(config.get("imapFolder"));
        if (folderName.isBlank()) {
            folderName = DEFAULT_FOLDER;
        }
        // 收件人过滤：留空就只认发给 IMAP 账号本身的邮件
        String recipient = str(config.get("imapRecipient"));
        if (recipient.isBlank()) {
            recipient = username;
        }
        boolean markRead = boolOf(config.get("imapMarkRead"), true);

        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", host);
        props.put("mail.imaps.port", String.valueOf(port));
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.connectiontimeout", "8000");
        props.put("mail.imaps.timeout", "10000");

        Session session = Session.getInstance(props);
        Date since = parseSince(str(config.get("imapSince")));
        log.info("[邮件进线] 开始轮询 tenant={} channelId={} host={} user={} folder={} 起点={}",
                channel.getTenantCode(), channel.getId(), host, username, folderName,
                since == null ? "不限" : since);
        int handled = 0;
        int duplicated = 0;
        int scanned = 0;
        try (Store store = session.getStore("imaps")) {
            store.connect(host, port, username, password);
            sendClientId(store);
            try (Folder folder = store.getFolder(folderName)) {
                folder.open(Folder.READ_WRITE);
                int total = folder.getMessageCount();
                int start = Math.max(1, total - SCAN_LIMIT + 1);
                Message[] messages = total == 0 ? new Message[0] : folder.getMessages(start, total);
                log.info("[邮件进线] 邮箱共 {} 封，本轮从最新往回检查 {} 封", total, messages.length);
                for (int index = messages.length - 1; index >= 0; index--) {
                    Message message = messages[index];
                    if (handled >= maxPerRound) {
                        log.info("[邮件进线] 达到单轮上限 {} 封，剩下的下一轮继续", maxPerRound);
                        break;
                    }
                    scanned++;
                    try {
                        Date received = receivedDate(message);
                        if (since != null && received != null && received.before(since)) {
                            log.info("[邮件进线] 遇到早于起点的邮件（{}），本轮到此为止", received);
                            break;
                        }
                        String from = firstAddress(message.getFrom());
                        if (from == null || from.isBlank()) {
                            continue;
                        }
                        if (isOurOwnMail(message)) {
                            log.info("[邮件进线] 跳过平台自己发出的邮件（Auto-Submitted / X-SupportFlow-Mail）| {}",
                                    safeSubject(message));
                            continue;
                        }
                        // 系统通知 / 退信 / 营销群发不是客户来信：不建会话、不建工单，
                        // 否则会以"发件人显示名"当客户名，堆进坐席的待接单列表。
                        if (isSystemOrBulkMail(message, from)) {
                            log.info("[邮件进线] 跳过系统 / 营销类邮件（机器发件人或批量投递标记）| from={} subject={}",
                                    from, safeSubject(message));
                            continue;
                        }
                        if (!matchesRecipient(message, recipient)) {
                            log.info("[邮件进线] 跳过：收件人不是 {} | {}", recipient, safeSubject(message));
                            continue;
                        }
                        // 不依赖"已读"标记：163 的 flags 经过 JavaMail 读出来恒为已读（实测），
                        // 只按 "时间起点 + Message-ID 去重" 判断该不该建单，才不会漏信。
                        MailInboundResultVO result = inboundService.mailInbound(channel,
                                new MailInbound(
                                        from, firstPersonal(message.getFrom()),
                                        message.getSubject() == null ? "(无主题)" : message.getSubject().trim(),
                                        extractText(message), messageIds(message)));
                        if (Boolean.TRUE.equals(result.duplicated())) {
                            duplicated++;
                            continue;
                        }
                        handled++;
                        if (markRead) {
                            try {
                                message.setFlag(Flags.Flag.SEEN, true);
                            } catch (Exception ignore) {
                                // 标记已读失败不影响建单（163 的标记操作也未必可靠）
                            }
                        }
                        log.info("[邮件进线] ✅ 已建单 ticketNo={} 主题={} 发件人={}",
                                result.ticketNo(), safeSubject(message), from);
                    } catch (Exception e) {
                        // 单封失败不能拖垮整轮：剩下的邮件还要处理
                        log.warn("[邮件进线] ❌ 单封处理失败 subject={} error={}",
                                safeSubject(message), e.getMessage(), e);
                    }
                }
            }
        }
        log.info("[邮件进线] 本轮结束 tenant={} channelId={} 检查 {} 封、新建 {} 封、之前已处理 {} 封",
                channel.getTenantCode(), channel.getId(), scanned, handled, duplicated);
    }

    /** 收信时间：优先服务端 INTERNALDATE，取不到再用邮件自身的时间 */
    private static Date receivedDate(Message message) throws Exception {
        Date received = message.getReceivedDate();
        return received != null ? received : message.getSentDate();
    }

    /** 解析 yyyy-MM-dd（当天 0 点）或 yyyy-MM-dd HH:mm；解析不了就当作没配 */
    private static Date parseSince(String text) {
        String value = text.trim().replace('T', ' ');
        try {
            LocalDateTime dateTime = value.length() <= 10
                    ? LocalDate.parse(value).atStartOfDay()
                    : LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            return Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant());
        } catch (Exception e) {
            log.warn("imapSince 格式不对（应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm），本次忽略：{}", text);
            return null;
        }
    }

    /**
     * 163 / 126 等网易邮箱要求客户端登录后先发一个 IMAP {@code ID} 命令（RFC 2971），
     * 否则会被判成「Unsafe Login」直接拒掉。JavaMail / Angus 只在显式调用 {@code id()} 时才发，
     * 所以这里补一下；其它服务商不支持 ID 也没关系，失败只记 debug 日志、不影响拉信。
     */
    private static void sendClientId(Store store) {
        if (store instanceof IMAPStore imapStore) {
            try {
                imapStore.id(Map.of(
                        "name", "supportflow-customer-service",
                        "version", "1.0",
                        "vendor", "supportflow"));
            } catch (Exception e) {
                log.debug("IMAP ID 命令发送失败（该邮箱可能不需要）：{}", e.getMessage());
            }
        }
    }

    /**
     * 这封信是不是平台自己发出的（防自回环）。
     *
     * <p>只认"平台自己发出的邮件"标记，不比对发件人地址：</p>
     * <ol>
     *   <li>{@code Auto-Submitted} 头不是 {@code no}（RFC 3834 的自动回复标记，平台发信会带）；</li>
     *   <li>带平台自定义头 {@code X-SupportFlow-Mail}。</li>
     * </ol>
     *
     * <p>为什么不比对"发件人是不是本邮箱"：本地演示常用同一个邮箱既当客户又当客服邮箱，
     * 那样会把真实客户来信一起误杀；自动回复标记是 RFC 3834 的标准做法，够用且不误伤。</p>
     */
    private static boolean isOurOwnMail(Message message) throws Exception {
        String[] auto = message.getHeader("Auto-Submitted");
        if (auto != null) {
            for (String value : auto) {
                if (value != null && !value.trim().isEmpty()
                        && !"no".equalsIgnoreCase(value.trim())) {
                    return true;
                }
            }
        }
        if (message.getHeader("X-SupportFlow-Mail") != null) {
            return true;
        }
        return false;
    }

    /**
     * 这封信是不是系统 / 营销 / 退信类邮件（不应当成客户来信）。
     *
     * <p>三类判断，命中任意一条就跳过，目的是拦下"发件人显示名被当成客户名"的脏数据：</p>
     * <ol>
     *   <li><b>机器发件人</b>：{@code postmaster@}、{@code mailer-daemon@}、{@code no-reply@}、
     *       {@code notification@} 等本地部分命中黑名单；</li>
     *   <li><b>批量投递标记</b>：带 {@code List-Unsubscribe} / {@code List-Id} /
     *       {@code X-Auto-Response-Suppress} / {@code Feedback-ID} 等头，或
     *       {@code Precedence: bulk/junk/list}——营销群发邮件的标准特征；</li>
     *   <li><b>投递状态通知</b>：{@code multipart/report} 类型的退信 / DSN。</li>
     * </ol>
     *
     * <p>为什么不比对发件人域名（如 aliyun.com / facebookmail.com）：域名列表永远追不完，
     * 换一家营销服务商就漏；上面这些是邮件协议层面的通用标记，覆盖更稳、也不会误伤真人客户。</p>
     */
    private static boolean isSystemOrBulkMail(Message message, String from) throws Exception {
        String local = senderLocalPart(from);
        if (local != null && MACHINE_SENDER_LOCALS.contains(local)) {
            return true;
        }
        if (headerHasAny(message,
                "List-Unsubscribe", "List-Id", "List-Post",
                "X-Auto-Response-Suppress", "Feedback-ID")) {
            return true;
        }
        String precedence = firstHeader(message, "Precedence");
        if (precedence != null) {
            String value = precedence.trim().toLowerCase(Locale.ROOT);
            if ("bulk".equals(value) || "junk".equals(value) || "list".equals(value)) {
                return true;
            }
        }
        String contentType = message.getContentType();
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("multipart/report");
    }

    /** 取发件人地址的本地部分（小写、去掉 +标签），用于匹配机器发件人黑名单 */
    private static String senderLocalPart(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String value = address.trim().toLowerCase(Locale.ROOT);
        int at = value.lastIndexOf('@');
        String local = at > 0 ? value.substring(0, at) : value;
        int plus = local.indexOf('+');
        if (plus > 0) {
            local = local.substring(0, plus);
        }
        return local.trim();
    }

    private static boolean headerHasAny(Message message, String... names) throws Exception {
        for (String name : names) {
            if (message.getHeader(name) != null) {
                return true;
            }
        }
        return false;
    }

    private static String firstHeader(Message message, String name) throws Exception {
        String[] values = message.getHeader(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    /** 收件人过滤：只处理发给指定地址的邮件（邮箱可能同时收别的信） */
    private static boolean matchesRecipient(Message message, String recipient) throws Exception {
        if (recipient == null || recipient.isBlank()) {
            return true;
        }
        for (Address address : allRecipients(message)) {
            if (address instanceof InternetAddress internet
                    && internet.getAddress() != null
                    && internet.getAddress().equalsIgnoreCase(recipient.trim())) {
                return true;
            }
        }
        return false;
    }

    private static Address[] allRecipients(Message message) throws Exception {
        List<Address> list = new ArrayList<>();
        if (message.getRecipients(Message.RecipientType.TO) != null) {
            list.addAll(List.of(message.getRecipients(Message.RecipientType.TO)));
        }
        if (message.getRecipients(Message.RecipientType.CC) != null) {
            list.addAll(List.of(message.getRecipients(Message.RecipientType.CC)));
        }
        return list.toArray(new Address[0]);
    }

    /** 取正文：优先纯文本，只有 HTML 时把标签去掉（客服看的是人话，不是源码） */
    private static String extractText(Message message) throws Exception {
        return textOf(message.getContent());
    }

    private static String textOf(Object content) throws Exception {
        if (content == null) {
            return "";
        }
        if (content instanceof String text) {
            return stripHtml(text);
        }
        if (content instanceof Multipart multipart) {
            String html = null;
            for (int index = 0; index < multipart.getCount(); index++) {
                BodyPart part = multipart.getBodyPart(index);
                String type = part.getContentType() == null ? "" : part.getContentType().toLowerCase();
                if (type.startsWith("text/plain")) {
                    return textOf(part.getContent());
                }
                if (type.startsWith("text/html") && html == null) {
                    html = textOf(part.getContent());
                }
            }
            return html == null ? "" : html;
        }
        return "";
    }

    /** 极简 HTML 去标签：够用即可（复杂邮件交给前端渲染不在这一步做） */
    private static String stripHtml(String text) {
        if (!text.contains("<")) {
            return text.trim();
        }
        return text.replaceAll("(?s)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private static String messageIds(Message message) throws Exception {
        String[] ids = message.getHeader("Message-ID");
        return ids == null || ids.length == 0 ? null : ids[0];
    }

    private static String firstAddress(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return null;
        }
        if (addresses[0] instanceof InternetAddress internet) {
            return internet.getAddress();
        }
        return String.valueOf(addresses[0]);
    }

    private static String firstPersonal(Address[] addresses) {
        if (addresses == null || addresses.length == 0 || !(addresses[0] instanceof InternetAddress internet)) {
            return null;
        }
        return internet.getPersonal();
    }

    private static String safeSubject(Message message) {
        try {
            return message.getSubject();
        } catch (Exception e) {
            return "(读不到主题)";
        }
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

    private static boolean boolOf(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return fallback;
        }
        return !"false".equalsIgnoreCase(text) && !"0".equals(text);
    }
}
