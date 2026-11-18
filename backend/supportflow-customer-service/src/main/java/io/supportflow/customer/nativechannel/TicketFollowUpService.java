package io.supportflow.customer.nativechannel;

import io.supportflow.customer.dto.session.CsatResult;
import io.supportflow.customer.dto.ticket.SurveyOutcome;
import io.supportflow.customer.dto.ticket.SurveyState;
import io.supportflow.customer.entity.Channel;
import io.supportflow.customer.entity.Session;
import io.supportflow.customer.entity.Ticket;
import io.supportflow.customer.mapper.ChannelMapper;
import io.supportflow.customer.mapper.SessionMapper;
import io.supportflow.customer.mapper.TicketMapper;
import io.supportflow.customer.service.session.SessionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 工单解决后的**自动回访**（邮件渠道）。
 *
 * <p>演示页承诺的是"结单自动回访、满意度回写客户 360"，这一段把它补上：</p>
 * <ol>
 *   <li>工单置为「已解决」时，找到它的来源会话；</li>
 *   <li>只有**邮件渠道**才发（客户不在平台上，唯一能触达的通道就是他的邮箱；App 走推送、
 *       小程序走订阅消息，各有一套，不在这里混）；</li>
 *   <li>往会话里写一条"客户可见"的系统消息，内容是回访模板 + 带签名的评价链接
 *       —— 复用既有的「会话消息 → 原生渠道出站」链路投递（坐席回复邮件走的就是它），
 *       所以 SMTP / 邮件网关两种发信方式都自动生效；</li>
 *   <li>客户点链接打分后，由 {@link SessionService#recordTicketSurvey} 写 csat_record
 *       并回写客户满意度——**和会话评价同一张表、同一套口径**，客户 360 不会出现两个数。</li>
 * </ol>
 *
 * <p>三条边界：同一工单只发一次（clientMsgNo 幂等）；发送失败只记日志、绝不影响工单流转；
 * 链接带签名（HMAC），工单号被别人猜到也改不了别人的评价。</p>
 */
@Service
public class TicketFollowUpService {

    private static final Logger log = LoggerFactory.getLogger(TicketFollowUpService.class);

    /** 只对邮件渠道做自动回访 */
    private static final int CHANNEL_TYPE_MAIL = 6;
    /** 工单状态：4-已解决、5-已关闭（这两个状态才允许评价） */
    private static final int TICKET_RESOLVED = 4;
    private static final int TICKET_CLOSED = 5;

    private static final String DEFAULT_TEMPLATE = """
            您好，您在工单 {ticketNo} 中反馈的问题已经处理完成。
            麻烦花 10 秒评价一下本次服务（点开下面链接，选 1~5 分即可）：
            {link}""";

    private final TicketMapper ticketMapper;
    private final SessionMapper sessionMapper;
    private final ChannelMapper channelMapper;
    private final ChannelNativeConfigService configService;
    private final SessionService sessionService;
    /** 评价链接的对外前缀（与原生渠道回调地址同一个配置；没配就用本地网关兜底） */
    private final String baseUrl;
    /** 签名密钥：复用 JWT 密钥（同一套部署只此一份，不额外引入配置） */
    private final String signKey;

    public TicketFollowUpService(TicketMapper ticketMapper, SessionMapper sessionMapper,
                                 ChannelMapper channelMapper, ChannelNativeConfigService configService,
                                 SessionService sessionService,
                                 @Value("${supportflow.native.callback-base-url:}") String callbackBaseUrl,
                                 @Value("${supportflow.auth.jwt-secret}") String jwtSecret) {
        this.ticketMapper = ticketMapper;
        this.sessionMapper = sessionMapper;
        this.channelMapper = channelMapper;
        this.configService = configService;
        this.sessionService = sessionService;
        String base = callbackBaseUrl == null || callbackBaseUrl.isBlank()
                ? "http://localhost:9090" : callbackBaseUrl.replaceAll("/+$", "");
        if (callbackBaseUrl == null || callbackBaseUrl.isBlank()) {
            log.warn("未配置 supportflow.native.callback-base-url，回访链接用本地默认地址 {}（生产必须配成公网地址）", base);
        }
        this.baseUrl = base;
        this.signKey = jwtSecret == null ? "" : jwtSecret;
    }

    /**
     * 工单解决了就安排一次回访。**失败只记日志**：回访是增值动作，不能反过来把工单流转搞失败。
     *
     * @return 是否真的安排了回访（只有「来源会话 + 邮件渠道」才会 true，调用方据此写一条工单流转记录）
     */
    public boolean sendAfterResolved(Ticket ticket) {
        if (ticket == null || ticket.getSessionNo() == null || ticket.getSessionNo().isBlank()) {
            return false;
        }
        try {
            Session session = sessionMapper.selectOne(Wrappers.<Session>lambdaQuery()
                    .eq(Session::getTenantCode, ticket.getTenantCode())
                    .eq(Session::getSessionNo, ticket.getSessionNo())
                    .last("LIMIT 1"));
            if (session == null || session.getChannelId() == null) {
                return false;
            }
            Channel channel = channelMapper.selectById(session.getChannelId());
            if (channel == null || channel.getChannelType() == null
                    || channel.getChannelType() != CHANNEL_TYPE_MAIL) {
                return false;
            }
            Map<String, Object> config = configService.raw(ticket.getTenantCode(), channel.getId());
            Object templateValue = config.get("satisfactionTemplate");
            String template = templateValue == null || String.valueOf(templateValue).isBlank()
                    ? DEFAULT_TEMPLATE : String.valueOf(templateValue);
            String content = template
                    .replace("{ticketNo}", String.valueOf(ticket.getTicketNo()))
                    .replace("{link}", surveyUrl(ticket.getTenantCode(), ticket.getTicketNo()));
            // clientMsgNo 幂等：重开再解决一次也不会给客户发第二封
            sessionService.appendMessage(ticket.getTenantCode(), session.getSessionNo(),
                    SessionService.SENDER_SYSTEM, null, SessionService.MSG_TYPE_TEXT, content,
                    SessionService.VISIBLE_ALL, "ticket-survey-" + ticket.getTicketNo());
            log.info("工单解决后已安排回访（邮件）tenant={} ticketNo={} session={}",
                    ticket.getTenantCode(), ticket.getTicketNo(), session.getSessionNo());
            return true;
        } catch (Exception e) {
            log.warn("回访安排失败（不影响工单流转）tenant={} ticketNo={} error={}",
                    ticket.getTenantCode(), ticket.getTicketNo(), e.getMessage());
            return false;
        }
    }

    /** 评价链接：带签名的公开地址，客户在邮件里点开就能打分（不需要登录） */
    public String surveyUrl(String tenantCode, String ticketNo) {
        return baseUrl + "/api/customer/tickets/survey?t=" + tenantCode
                + "&no=" + ticketNo + "&sig=" + sign(tenantCode, ticketNo);
    }

    /** 校验评价链接的签名（防止有人拿别的工单号瞎打分） */
    public boolean validSignature(String tenantCode, String ticketNo, String signature) {
        if (tenantCode == null || ticketNo == null || signature == null) {
            return false;
        }
        return sign(tenantCode, ticketNo).equals(signature);
    }

    /**
     * 客户点开评价链接后的处理：先看工单能不能评，再看这次是"打开表单"还是"提交分数"。
     */
    public SurveyOutcome survey(String tenantCode, String ticketNo, Integer score) {
        Ticket ticket = ticketMapper.selectOne(Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getTenantCode, tenantCode)
                .eq(Ticket::getTicketNo, ticketNo)
                .eq(Ticket::getDeleted, false)
                .last("LIMIT 1"));
        if (ticket == null) {
            return SurveyOutcome.of(SurveyState.NOT_FOUND, "没有找到这个工单，可能链接不完整。");
        }
        int status = ticket.getStatus() == null ? 0 : ticket.getStatus();
        if (status != TICKET_RESOLVED && status != TICKET_CLOSED) {
            return SurveyOutcome.of(SurveyState.PENDING, "这个工单还在处理中，处理完再请您评价～");
        }
        if (score == null) {
            return SurveyOutcome.of(SurveyState.FORM, ticket.getTitle());
        }
        if (ticket.getSessionNo() == null || ticket.getSessionNo().isBlank()) {
            return SurveyOutcome.of(SurveyState.NOT_FOUND, "这个工单没有关联会话，暂时不能评价。");
        }
        try {
            CsatResult result = sessionService.recordTicketSurvey(
                    tenantCode, ticket.getSessionNo(), score, null, "邮件回访");
            return SurveyOutcome.of(SurveyState.DONE, "感谢您的评价，我们记下了："
                    + result.score() + " 分。");
        } catch (Exception e) {
            log.warn("回访打分失败 tenant={} ticketNo={} score={} error={}",
                    tenantCode, ticketNo, score, e.getMessage());
            return SurveyOutcome.of(SurveyState.NOT_FOUND, "评价没能记录：" + e.getMessage());
        }
    }

    /** 签名：HMAC-SHA256(密钥, tenant|ticketNo) → base64url 前 16 位（够不可猜，又不至于让链接太长） */
    private String sign(String tenantCode, String ticketNo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((tenantCode + "|" + ticketNo).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 16);
        } catch (Exception e) {
            log.warn("评价链接签名失败：{}", e.getMessage());
            return "";
        }
    }
}
