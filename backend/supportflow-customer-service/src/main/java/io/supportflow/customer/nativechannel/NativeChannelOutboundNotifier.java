package io.supportflow.customer.nativechannel;

import io.supportflow.customer.dto.channel.MailSmtpSettings;
import io.supportflow.customer.entity.Channel;
import io.supportflow.customer.entity.ChannelIdentity;
import io.supportflow.customer.entity.Session;
import io.supportflow.customer.event.SessionMessageEvent;
import io.supportflow.customer.mapper.ChannelMapper;
import io.supportflow.customer.mapper.SessionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * 原生渠道出站：把机器人 / 坐席的回复送回 App 与邮件。
 *
 * <p>与公众号那套并列：微信生态（2/3）由 {@code WeChatOutboundNotifier} 负责，
 * 这里负责 App（4，交给企业推送服务下发 APNs / FCM）与邮件（6，交给邮件网关投递）。
 * 电话（5）通话中没有文本回推通道，挂机小结走工单；网站（1）本来就在页面上看。</p>
 *
 * <p>三条硬约束和微信一致：只推客户可见的**文本**、模拟身份（{@code sim_} 前缀）不真发、失败只记日志。</p>
 */
@Component
public class NativeChannelOutboundNotifier {

    private static final int SENDER_CUSTOMER = 1;
    private static final int VISIBLE_ALL = 1;
    private static final int MSG_TYPE_TEXT = 1;
    private static final String SIMULATED_PREFIX = "sim_";

    private static final Logger log = LoggerFactory.getLogger(NativeChannelOutboundNotifier.class);

    private final SessionMapper sessionMapper;
    private final ChannelMapper channelMapper;
    private final ChannelIdentityService identityService;
    private final ChannelNativeConfigService configService;
    private final NativeOutboundClient outboundClient;
    /** 邮件出站（方案 A：SMTP 直发）；没配 SMTP 时自动退回上面的 webhook 方式 */
    private final MailOutboundSender mailSender;

    public NativeChannelOutboundNotifier(SessionMapper sessionMapper, ChannelMapper channelMapper,
                                         ChannelIdentityService identityService,
                                         ChannelNativeConfigService configService,
                                         NativeOutboundClient outboundClient,
                                         MailOutboundSender mailSender) {
        this.sessionMapper = sessionMapper;
        this.channelMapper = channelMapper;
        this.identityService = identityService;
        this.configService = configService;
        this.outboundClient = outboundClient;
        this.mailSender = mailSender;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessage(SessionMessageEvent event) {
        if (event.senderType() == SENDER_CUSTOMER
                || event.visibleTo() != VISIBLE_ALL
                || event.msgType() != MSG_TYPE_TEXT
                || event.content() == null || event.content().isBlank()) {
            return;
        }
        try {
            Session session = sessionMapper.selectOne(Wrappers.<Session>lambdaQuery()
                    .eq(Session::getTenantCode, event.tenantCode())
                    .eq(Session::getSessionNo, event.sessionNo())
                    .last("LIMIT 1"));
            if (session == null || session.getChannelId() == null || session.getCustomerId() == null) {
                return;
            }
            Channel channel = channelMapper.selectById(session.getChannelId());
            if (channel == null || channel.getChannelType() == null) {
                return;
            }
            int type = channel.getChannelType();
            if (type != 4 && type != 6) {
                return;
            }
            String identityType = type == 4 ? ChannelIdentityService.TYPE_APP_USER : ChannelIdentityService.TYPE_MAIL;
            ChannelIdentity identity = identityService.bySessionCustomer(event.tenantCode(),
                    channel.getId(), session.getCustomerId());
            if (identity == null) {
                identity = identityService.bySessionCustomer(event.tenantCode(), channel.getId(), session.getCustomerId());
            }
            if (identity == null || identity.getExternalId() == null) {
                return;
            }
            if (identity.getExternalId().startsWith(SIMULATED_PREFIX)) {
                log.info("模拟身份不真发（{}）identity={}", type == 4 ? "App 推送" : "邮件", identity.getExternalId());
                return;
            }
            Map<String, Object> config = configService.raw(event.tenantCode(), channel.getId());
            if (type == 4) {
                outboundClient.pushToApp(str(config.get("pushWebhook")), str(config.get("pushSecret")),
                        identity.getExternalId(), "客服回复", event.content(), event.sessionNo());
            } else {
                // 邮件出站：用渠道配置里的 SMTP 直发（每个租户用自己的客服邮箱）。
                // 没配 SMTP 时，才退回历史遗留的网关推送（早期渠道可能还存着发信接口地址）。
                MailSmtpSettings smtp = mailSender.resolve(config);
                if (smtp != null) {
                    mailSender.sendReply(smtp, identity.getExternalId(), subjectOf(config, event.sessionNo()),
                            event.content(), event.sessionNo());
                } else {
                    outboundClient.sendMail(str(config.get("outboundWebhook")), str(config.get("outboundSecret")),
                            identity.getExternalId(), "客服回复 · " + event.sessionNo(), event.content(), null);
                }
            }
        } catch (Exception e) {
            log.warn("原生渠道出站失败 tenant={} sessionNo={} error={}",
                    event.tenantCode(), event.sessionNo(), e.getMessage());
        }
    }

    /** 邮件主题：配置里存了原始主题就用它（回信同线程），否则用会话号兜底 */
    private static String subjectOf(Map<String, Object> config, String sessionNo) {
        String subject = str(config.get("outboundSubject"));
        return subject.isBlank() ? "客服回复 · " + sessionNo : subject;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
