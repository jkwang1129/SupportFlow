package io.supportflow.customer.nativechannel;

import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.customer.dto.channel.AppInboundResultVO;
import io.supportflow.customer.dto.channel.MailInbound;
import io.supportflow.customer.dto.channel.MailInboundResultVO;
import io.supportflow.customer.dto.channel.MailIntent;
import io.supportflow.customer.dto.channel.MailSmtpSettings;
import io.supportflow.customer.dto.channel.PhoneEvent;
import io.supportflow.customer.dto.channel.PhoneEventResultVO;
import io.supportflow.customer.dto.session.OpenCommand;
import io.supportflow.customer.dto.session.OpenResult;
import io.supportflow.customer.dto.ticket.ChannelTicketCommand;
import io.supportflow.customer.dto.ticket.TicketVO;
import io.supportflow.customer.entity.Channel;
import io.supportflow.customer.entity.ChannelCallRecord;
import io.supportflow.customer.entity.ChannelIdentity;
import io.supportflow.customer.entity.ChannelInboundLog;
import io.supportflow.customer.entity.Customer;
import io.supportflow.customer.mapper.ChannelInboundLogMapper;
import io.supportflow.customer.mapper.CustomerMapper;
import io.supportflow.customer.service.session.SessionService;
import io.supportflow.customer.service.ticket.TicketService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import io.supportflow.customer.service.phone.PhoneCallService;
import io.supportflow.customer.service.phone.PhoneCallSpeechService;
import io.supportflow.customer.service.session.RoutingService;

/**
 * 原生渠道进线：App 消息 / 邮件进线 / 400 呼叫事件。
 *
 * <p>三个渠道共用同一套骨架：**认渠道 → 幂等 → 身份绑客户 → 开会话 → 落库（自动触发机器人）**，
 * 差别只在"进来的是什么"和"要不要建单"：</p>
 * <ul>
 *   <li>App：进来一条文本消息，落库即可，回复由出站推送交给企业的推送服务；</li>
 *   <li>邮件：进来一封邮件，除了落库还要**自动建工单 + 秒级回执**（客户发邮件最怕石沉大海）；</li>
 *   <li>400：进来的是呼叫事件（呼入 / 按键 / 挂机），前两个返回**播报文本**给呼叫中心做 TTS，
 *       挂机时把通话内容落库并**自动建单**（通话小结）。</li>
 * </ul>
 */
@Service
public class ChannelNativeInboundService {

    private static final Logger log = LoggerFactory.getLogger(ChannelNativeInboundService.class);

    private final ChannelNativeConfigService configService;
    private final ChannelIdentityService identityService;
    private final ChannelInboundLogMapper inboundLogMapper;
    private final CustomerMapper customerMapper;
    private final SessionService sessionService;
    private final TicketService ticketService;
    private final NativeOutboundClient outboundClient;
    /** 方案 A 的 SMTP 直发（按渠道解析发信参数）：受理回执 / 坐席回复 / 结单回访都走它 */
    private final MailOutboundSender mailSender;
    private final SnowflakeIdGenerator idGenerator;
    /** 400 通话记录：呼入/按键/接听/录音/挂机叠成一条可查的呼叫记录 */
    private final PhoneCallService phoneCallService;
    /** 转人工派单：按键选人工后立刻按技能组挑空闲坐席（挑不到就留在待接待队列） */
    private final RoutingService routingService;
    /** 400 录音转写：录音回传后异步调 supportflow-ai 做一句话识别，把文本回填到通话记录 */
    private final PhoneCallSpeechService phoneCallSpeechService;

    public ChannelNativeInboundService(ChannelNativeConfigService configService,
                                       ChannelIdentityService identityService,
                                       ChannelInboundLogMapper inboundLogMapper,
                                       CustomerMapper customerMapper,
                                       SessionService sessionService,
                                       TicketService ticketService,
                                       NativeOutboundClient outboundClient,
                                       MailOutboundSender mailSender,
                                       SnowflakeIdGenerator idGenerator,
                                       PhoneCallService phoneCallService,
                                       RoutingService routingService,
                                       PhoneCallSpeechService phoneCallSpeechService) {
        this.configService = configService;
        this.identityService = identityService;
        this.inboundLogMapper = inboundLogMapper;
        this.customerMapper = customerMapper;
        this.sessionService = sessionService;
        this.ticketService = ticketService;
        this.outboundClient = outboundClient;
        this.mailSender = mailSender;
        this.idGenerator = idGenerator;
        this.phoneCallService = phoneCallService;
        this.routingService = routingService;
        this.phoneCallSpeechService = phoneCallSpeechService;
    }

    // ─────────────────────────────────────────────────────────── App

    /** App 用户发来一条消息（HTTP 通道；WebSocket 通道由实时网关走同一套会话落库） */
    public AppInboundResultVO appMessage(Channel channel, String userId, String userName,
                                         String content, String clientMsgNo) {
        if (userId == null || userId.isBlank()) {
            throw new BizException(40001, "缺少 App 用户标识");
        }
        if (content == null || content.isBlank()) {
            throw new BizException(40001, "消息内容不能为空");
        }
        String dedupeKey = "app:" + (clientMsgNo == null || clientMsgNo.isBlank()
                ? userId + ":" + System.nanoTime() : clientMsgNo);
        if (!markProcessed(channel, dedupeKey, ChannelIdentityService.TYPE_APP_USER, "app_message")) {
            return new AppInboundResultVO(true, null, null, null,
                    "这条消息已经处理过（clientMsgNo 幂等）");
        }
        Target target = bindOrCreate(channel, ChannelIdentityService.TYPE_APP_USER, userId,
                userName == null || userName.isBlank() ? "App 用户" : userName);
        sessionService.appendMessage(channel.getTenantCode(), target.sessionNo(),
                SessionService.SENDER_CUSTOMER, null, SessionService.MSG_TYPE_TEXT, content,
                SessionService.VISIBLE_ALL, clientMsgNo);
        Map<String, Object> config = configService.raw(channel.getTenantCode(), channel.getId());
        return new AppInboundResultVO(null, target.sessionNo(), target.customerNo(),
                String.valueOf(config.getOrDefault("welcomeText", "")),
                "消息已进入会话，机器人/坐席的回复会推给你的推送服务");
    }

    // ─────────────────────────────────────────────────────────── 邮件

    /** 邮件进线：自动受理回执 + 解析诉求 + 建工单 */
    @Transactional
    public MailInboundResultVO mailInbound(Channel channel, MailInbound mail) {
        if (mail.from() == null || mail.from().isBlank()) {
            throw new BizException(40001, "缺少发件人");
        }
        String dedupeKey = "mail:" + (mail.messageId() == null || mail.messageId().isBlank()
                ? mail.from() + ":" + (mail.subject() == null ? "" : mail.subject()) + ":" + mail.text().hashCode()
                : mail.messageId());
        if (!markProcessed(channel, dedupeKey, ChannelIdentityService.TYPE_MAIL, "mail_inbound")) {
            return new MailInboundResultVO(true, null, null, null, null, null, null, null, null,
                    "这封邮件已经处理过（Message-ID 幂等）");
        }
        Map<String, Object> config = configService.raw(channel.getTenantCode(), channel.getId());
        String fromName = mail.fromName() == null || mail.fromName().isBlank() ? mail.from() : mail.fromName();
        Target target = bindOrCreate(channel, ChannelIdentityService.TYPE_MAIL, mail.from(), fromName);
        String body = (mail.subject() == null ? "" : mail.subject() + "\n") + (mail.text() == null ? "" : mail.text());
        sessionService.appendMessage(channel.getTenantCode(), target.sessionNo(),
                SessionService.SENDER_CUSTOMER, null, SessionService.MSG_TYPE_TEXT,
                "【邮件】" + body, SessionService.VISIBLE_ALL, "mail-" + dedupeKey);

        MailIntent intent = parseIntent(body);
        int priority = intOf(config.get("defaultPriority"), intent.priority());
        TicketVO ticket = ticketService.createFromChannel(channel.getTenantCode(), channel.getName(),
                new ChannelTicketCommand(
                        mail.subject() == null || mail.subject().isBlank() ? intent.title() : mail.subject(),
                        body, null, priority, target.customerId(), target.customerName(),
                        3, target.sessionNo(), null, null, null));

        String receipt = template(config.get("autoReplyTemplate"),
                "您好，您的邮件已受理，工单号 {ticketNo}，我们会在 1 个工作日内处理并回复。")
                .replace("{ticketNo}", ticket.ticketNo())
                .replace("{intent}", intent.label());
        // 回执用渠道配置里的 SMTP 直发（和坐席回复、结单回访同一条路）：
        // 每个租户用自己的客服邮箱发信。没配 SMTP 时才退回历史遗留的网关推送。
        MailSmtpSettings smtp = mailSender.resolve(config);
        boolean receiptSent;
        if (smtp != null) {
            receiptSent = mailSender.sendText(smtp, mail.from(),
                    "回执：您的邮件已受理（" + ticket.ticketNo() + "）", receipt, mail.messageId());
        } else {
            receiptSent = outboundClient.sendMail(str(config.get("outboundWebhook")), str(config.get("outboundSecret")),
                    mail.from(), "回执：您的邮件已受理（" + ticket.ticketNo() + "）", receipt, mail.messageId());
        }
        if (!receiptSent) {
            log.warn("受理回执未发出（渠道既没配 SMTP 也没配发信网关）tenant={} ticketNo={}",
                    channel.getTenantCode(), ticket.ticketNo());
        }
        log.info("邮件进线 tenant={} channel={} from={} ticketNo={} 意图={} 优先级={}",
                channel.getTenantCode(), channel.getName(), mail.from(), ticket.ticketNo(),
                intent.label(), priority);
        // SLA 截止时间一并回给调用方（网关页面上直接能看到"这单最晚什么时候必须响应/解决"）
        return new MailInboundResultVO(null, target.sessionNo(), target.customerNo(),
                ticket.ticketNo(), intent.label(), priority, ticket.firstResponseDue(),
                ticket.resolveDue(), receiptSent, null);
    }

    // ─────────────────────────────────────────────────────────── 400 热线

    /** 呼叫事件：incoming 呼入 / dtmf 按键 / hangup 挂机 */
    public PhoneEventResultVO phoneEvent(Channel channel, PhoneEvent event) {
        if (event.callId() == null || event.callId().isBlank()) {
            throw new BizException(40001, "缺少 callId");
        }
        String type = event.event() == null ? "" : event.event().trim().toLowerCase();
        if (!markProcessed(channel, "phone:" + event.callId() + ":" + type + ":" + str(event.digits()),
                ChannelIdentityService.TYPE_PHONE, "phone_" + type)) {
            return PhoneEventResultVO.duplicated("这个呼叫事件已经处理过（callId + 事件 幂等）");
        }
        Map<String, Object> config = configService.raw(channel.getTenantCode(), channel.getId());
        String caller = event.from() == null || event.from().isBlank() ? "未知号码" : event.from();
        return switch (type) {
            case "incoming" -> {
                Target target = bindOrCreate(channel, ChannelIdentityService.TYPE_PHONE, caller, caller);
                phoneCallService.start(channel, event.callId(), caller, target.customerName(),
                        target.sessionNo(), target.customerNo());
                sessionService.appendMessage(channel.getTenantCode(), target.sessionNo(),
                        SessionService.SENDER_SYSTEM, null, SessionService.MSG_TYPE_TEXT,
                        "客户来电：" + caller, SessionService.VISIBLE_ALL, "phone-in-" + event.callId());
                yield PhoneEventResultVO.play(menuPrompt(config), menuOf(config),
                        target.sessionNo());
            }
            case "dtmf" -> {
                Target target = bindOrCreate(channel, ChannelIdentityService.TYPE_PHONE, caller, caller);
                phoneCallService.start(channel, event.callId(), caller, target.customerName(),
                        target.sessionNo(), target.customerNo());
                String digits = str(event.digits());
                Map<String, Object> item = menuItem(config, digits);
                String action = item == null ? "bot" : str(item.getOrDefault("action", "bot"));
                String label = item == null ? "其他问题" : str(item.getOrDefault("label", digits));
                phoneCallService.appendIvr(channel, event.callId(), digits, label);
                String say = "agent".equalsIgnoreCase(action)
                        ? "正在为您转接人工坐席，请稍候。"
                        : voiceAnswer(label);
                sessionService.appendMessage(channel.getTenantCode(), target.sessionNo(),
                        SessionService.SENDER_CUSTOMER, null, SessionService.MSG_TYPE_TEXT,
                        "客户按 " + digits + "（" + label + "）", SessionService.VISIBLE_ALL,
                        "phone-dtmf-" + event.callId() + "-" + digits);
                if ("agent".equalsIgnoreCase(action)) {
                    // 转人工：把会话放进待接待队列，坐席工作台立刻能看到
                    sessionService.appendMessage(channel.getTenantCode(), target.sessionNo(),
                            SessionService.SENDER_SYSTEM, null, SessionService.MSG_TYPE_TEXT,
                            "客户选择人工服务，已进入待接待队列", SessionService.VISIBLE_ALL,
                            "phone-agent-" + event.callId());
                    // 顺手按技能组派一次单：有人空闲就直接分给坐席（挑不到就留在队列里，
                    // 由坐席工作台/定时派单接手）——"客户选了人工但一直没人接"就是这么来的
                    try {
                        boolean assigned = routingService.assignIfPossible(channel.getTenantCode(), target.sessionNo());
                        log.info("400 转人工派单 tenant={} callId={} session={} assigned={}",
                                channel.getTenantCode(), event.callId(), target.sessionNo(), assigned);
                    } catch (Exception e) {
                        log.warn("400 转人工派单失败（已留在待接待队列）callId={} error={}",
                                event.callId(), e.getMessage());
                    }
                    phoneCallService.markTransferred(channel, event.callId(), target.sessionNo(),
                            event.agentName());
                }
                yield PhoneEventResultVO.dtmf("agent".equalsIgnoreCase(action), say, digits,
                        label, target.sessionNo());
            }
            // 人工接听（话务侧在坐席摘机时推）：记下接听时间与坐席，"等待多久"才有依据
            case "answered" -> {
                Target target = bindOrCreate(channel, ChannelIdentityService.TYPE_PHONE, caller, caller);
                phoneCallService.start(channel, event.callId(), caller, target.customerName(),
                        target.sessionNo(), target.customerNo());
                phoneCallService.markTransferred(channel, event.callId(), target.sessionNo(), event.agentName());
                yield PhoneEventResultVO.answered(target.sessionNo(), event.agentName());
            }
            // 录音回传：话务侧一般在挂机后几秒到几十秒推过来
            case "recording" -> {
                phoneCallService.start(channel, event.callId(), caller, null, null, null);
                phoneCallService.markRecording(channel, event.callId(), event.recordingUrl());
                // 转写异步做（下载 + 识别可能十几秒），webhook 立刻返回，别让话务侧等超时重推
                phoneCallSpeechService.transcribeLater(channel, event.callId(), event.recordingUrl());
                yield PhoneEventResultVO.recording(event.recordingUrl());
            }
            // 未接通：未接 / 客户放弃 / 线路异常——只留记录，不建单（否则工单里全是空电话）
            case "no_answer", "abandoned" -> {
                Target target = bindOrCreate(channel, ChannelIdentityService.TYPE_PHONE, caller, caller);
                phoneCallService.start(channel, event.callId(), caller, target.customerName(),
                        target.sessionNo(), target.customerNo());
                String reason = event.reason() == null || event.reason().isBlank()
                        ? ("abandoned".equals(type) ? "客户放弃等待" : "无人接听")
                        : event.reason();
                phoneCallService.markMissed(channel, event.callId(), reason);
                yield PhoneEventResultVO.missed(reason, target.sessionNo());
            }
            case "hangup" -> {
                Target target = bindOrCreate(channel, ChannelIdentityService.TYPE_PHONE, caller, caller);
                phoneCallService.start(channel, event.callId(), caller, target.customerName(),
                        target.sessionNo(), target.customerNo());
                String summary = template(config.get("summaryTemplate"),
                        "客户诉求：{intent}\n处理结果：{action}\n通话时长：{duration}")
                        .replace("{intent}", str(event.intent() == null ? "来电咨询" : event.intent()))
                        .replace("{action}", str(event.action() == null ? "AI 语音导航处理" : event.action()))
                        .replace("{duration}", str(event.duration() == null ? "—" : event.duration()));
                sessionService.appendMessage(channel.getTenantCode(), target.sessionNo(),
                        SessionService.SENDER_SYSTEM, null, SessionService.MSG_TYPE_TEXT,
                        summary, SessionService.VISIBLE_ALL, "phone-summary-" + event.callId());
                // 一条通话只建一张工单：重试/重复的挂机事件（callId+hangup 幂等之外的情况）不会再建
                ChannelCallRecord existing = phoneCallService.detailOf(channel.getTenantCode(), event.callId());
                TicketVO ticket = existing != null && existing.getTicketNo() != null
                        ? null
                        : ticketService.createFromChannel(channel.getTenantCode(), channel.getName(),
                        new ChannelTicketCommand(
                                "来电：" + caller + " · " + str(event.intent() == null ? "来电咨询" : event.intent()),
                                summary, null, intOf(config.get("defaultPriority"), 2),
                                target.customerId(), target.customerName(), 2, target.sessionNo(), null, null, null));
                String ticketNo = ticket == null
                        ? (existing == null ? null : existing.getTicketNo())
                        : ticket.ticketNo();
                phoneCallService.finish(channel, event.callId(), intOrNull(event.duration()),
                        summary, ticketNo, target.sessionNo());
                log.info("400 挂机建单 tenant={} callId={} ticketNo={}", channel.getTenantCode(),
                        event.callId(), ticketNo);
                yield PhoneEventResultVO.hangup(summary, ticketNo, target.sessionNo());
            }
            default -> throw new BizException(40001, "不认识的事件类型：" + type
                    + "（支持 incoming / dtmf / answered / recording / no_answer / abandoned / hangup）");
        };
    }

    // ─────────────────────────────────────────────────────────── 公共

    /** 认渠道 → 建/取客户 → 开会话（复用核心链路：新建会话会自动触发机器人接待） */
    private Target bindOrCreate(Channel channel, String identityType, String externalId, String displayName) {
        ChannelIdentity identity = identityService.find(channel.getId(), identityType, externalId);
        if (identity != null && identity.getSessionNo() != null && !identity.getSessionNo().isBlank()) {
            return new Target(identity.getSessionNo(), identity.getCustomerNo(), identity.getCustomerId(),
                    identity.getCustomerName());
        }
        String appKey = configService.appKeyOf(channel.getId());
        OpenResult opened = sessionService.openSession(
                new OpenCommand(appKey, null, displayName, null));
        Customer customer = customerMapper.selectOne(Wrappers.<Customer>lambdaQuery()
                .eq(Customer::getTenantCode, channel.getTenantCode())
                .eq(Customer::getCustomerNo, opened.customerNo())
                .last("LIMIT 1"));
        identityService.bind(channel.getTenantCode(), channel.getId(), identityType, externalId,
                customer == null ? null : customer.getId(), opened.customerNo(), opened.customerName(),
                opened.sessionNo());
        return new Target(opened.sessionNo(), opened.customerNo(),
                customer == null ? null : customer.getId(), opened.customerName());
    }

    /** 幂等：写一条去重日志，撞唯一索引说明重复推送 */
    public boolean markProcessed(Channel channel, String dedupeKey, String identityType, String eventType) {
        try {
            inboundLogMapper.insert(ChannelInboundLog.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(channel.getTenantCode())
                    .channelId(channel.getId())
                    .identityType(identityType)
                    .dedupeKey(dedupeKey)
                    .eventType(eventType)
                    .createTime(LocalDateTime.now())
                    .deleted(false)
                    .build());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 邮件诉求的规则解析：命中关键词就给标题、意图与优先级（接上大模型后把这里换成模型调用即可）。
     *
     * <p>关键词覆盖两类行业：电商（退款/物流）与在线教育（退费/试听/上课）——
     * 教培机构的来信里"退费""试听""直播进不去"是最常见的三类，不能只认电商词。</p>
     */
    MailIntent parseIntent(String body) {
        String text = body == null ? "" : body;
        if (contains(text, "退款", "退货", "售后", "维修", "换货", "退费", "转班", "换班")) {
            return new MailIntent("退费与售后", "售后诉求", 2);
        }
        if (contains(text, "发票", "开票", "抬头")) {
            return new MailIntent("发票与开票", "发票诉求", 3);
        }
        if (contains(text, "物流", "快递", "发货", "到货")) {
            return new MailIntent("物流与配送", "物流诉求", 3);
        }
        // 在线教育：试听预约与上课问题
        if (contains(text, "试听", "体验课", "预约课")) {
            return new MailIntent("试听预约", "试听诉求", 3);
        }
        if (contains(text, "上课", "直播", "开课", "排课", "回放", "课程")) {
            return new MailIntent("上课与课程", "课程诉求", 3);
        }
        if (contains(text, "投诉", "态度", "差评")) {
            return new MailIntent("投诉与建议", "投诉诉求", 1);
        }
        return new MailIntent("邮件咨询", "一般咨询", 3);
    }

    private static boolean contains(String text, String... keys) {
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private String menuPrompt(Map<String, Object> config) {
        List<Map<String, Object>> menu = menuOf(config);
        StringBuilder builder = new StringBuilder("您好，这里是SupportFlow 客服热线，请按键选择：");
        for (Map<String, Object> item : menu) {
            builder.append("按 ").append(str(item.get("key"))).append(" ")
                    .append(str(item.get("label"))).append("；");
        }
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> menuOf(Map<String, Object> config) {
        Object menu = config.get("ivrMenu");
        if (menu instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
            return result;
        }
        if (menu instanceof String text && !text.isBlank()) {
            try {
                return new ObjectMapper()
                        .readValue(text, new TypeReference<>() {
                        });
            } catch (Exception e) {
                log.warn("IVR 菜单 JSON 解析失败：{}", e.getMessage());
            }
        }
        return List.of();
    }

    private Map<String, Object> menuItem(Map<String, Object> config, String digits) {
        for (Map<String, Object> item : menuOf(config)) {
            if (digits.equals(str(item.get("key")))) {
                return item;
            }
        }
        return null;
    }

    /** 语音播报文本：呼叫中心负责 TTS，这里只给"播什么" */
    private String voiceAnswer(String label) {
        return switch (label) {
            case "订单查询" -> "已为您查询到最近一笔订单正在配送中，预计今天十八点前送达，详情已发送短信到您的手机。";
            case "售后服务" -> "已为您登记售后诉求，工程师会在四小时内与您联系，请保持电话畅通。";
            case "投诉建议" -> "您的意见已记录，主管会在两小时内回电，给您带来不便非常抱歉。";
            default -> "您的诉求已记录，我们会尽快为您处理。";
        };
    }

    private static String template(Object value, String fallback) {
        String text = str(value);
        return text.isBlank() ? fallback : text;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intOf(Object value, int fallback) {
        try {
            return Integer.parseInt(str(value).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 时长这类字段允许缺省：解析不出来就当没上报（不要填 0 把统计带偏） */
    private static Integer intOrNull(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Target(String sessionNo, String customerNo, Long customerId, String customerName) {
    }
}
