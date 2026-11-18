package io.supportflow.customer.wechat;

import io.supportflow.customer.entity.Session;
import io.supportflow.customer.entity.WeChatChannelConfig;
import io.supportflow.customer.entity.WeChatCustomerBind;
import io.supportflow.customer.event.SessionMessageEvent;
import io.supportflow.customer.mapper.SessionMapper;
import io.supportflow.customer.mapper.WeChatCustomerBindMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import io.supportflow.customer.service.session.SessionService;

/**
 * 把机器人 / 坐席的回复推回客户微信（公众号客服消息）。
 *
 * <p>订阅核心的 {@link SessionMessageEvent}：核心不认识微信，渠道自己接。
 * 三条硬约束写在代码里：</p>
 * <ol>
 *   <li><b>只推客户可见的消息</b>：内部备注（visibleTo=2）不能被推到客户微信里；</li>
 *   <li><b>只推文本</b>：图片消息微信侧要另走素材接口，这里先不推（客户可以在访客页看）；</li>
 *   <li><b>失败只记日志</b>：微信接口抖动不能影响聊天链路本身，客户侧消息已经落库了。</li>
 * </ol>
 */
@Component
public class WeChatOutboundNotifier {

    private static final Logger log = LoggerFactory.getLogger(WeChatOutboundNotifier.class);

    private final SessionMapper sessionMapper;
    private final WeChatCustomerBindMapper bindMapper;
    private final WeChatConfigService configService;
    private final WeChatAccessTokenService tokenService;
    private final WeChatApiClient apiClient;

    public WeChatOutboundNotifier(SessionMapper sessionMapper, WeChatCustomerBindMapper bindMapper,
                                  WeChatConfigService configService, WeChatAccessTokenService tokenService,
                                  WeChatApiClient apiClient) {
        this.sessionMapper = sessionMapper;
        this.bindMapper = bindMapper;
        this.configService = configService;
        this.tokenService = tokenService;
        this.apiClient = apiClient;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessage(SessionMessageEvent event) {
        if (event.senderType() == SessionServiceConstants.SENDER_CUSTOMER
                || event.visibleTo() != SessionServiceConstants.VISIBLE_ALL
                || event.msgType() != SessionServiceConstants.MSG_TYPE_TEXT
                || event.content() == null || event.content().isBlank()) {
            return;
        }
        try {
            Session session = sessionMapper.selectOne(Wrappers.<Session>lambdaQuery()
                    .eq(Session::getTenantCode, event.tenantCode())
                    .eq(Session::getSessionNo, event.sessionNo())
                    .last("LIMIT 1"));
            if (session == null || session.getCustomerId() == null) {
                return;
            }
            WeChatCustomerBind bind = bindMapper.selectOne(Wrappers.<WeChatCustomerBind>lambdaQuery()
                    .eq(WeChatCustomerBind::getTenantCode, event.tenantCode())
                    .eq(WeChatCustomerBind::getCustomerId, session.getCustomerId())
                    .eq(WeChatCustomerBind::getDeleted, false)
                    .orderByDesc(WeChatCustomerBind::getId)
                    .last("LIMIT 1"));
            if (bind == null || bind.getOpenid() == null) {
                return;
            }
            if (bind.getOpenid().startsWith(WeChatInboundService.SIMULATED_OPENID_PREFIX)) {
                log.info("模拟会话不推送微信 tenant={} sessionNo={}", event.tenantCode(), event.sessionNo());
                return;
            }
            WeChatChannelConfig config = configService.byAppId(bind.getAppId());
            if (config == null) {
                return;
            }
            apiClient.sendCustomText(tokenService.token(config), bind.getOpenid(), event.content());
        } catch (Exception e) {
            // 推不出去不影响会话本身；平台侧看这条日志就知道是微信接口的问题
            log.warn("微信回复推送失败 tenant={} sessionNo={} error={}",
                    event.tenantCode(), event.sessionNo(), e.getMessage());
        }
    }

    /** 常量集中在这里，避免和 SessionService 的字段常量换来换去 */
    private static final class SessionServiceConstants {
        static final int SENDER_CUSTOMER = 1;
        static final int VISIBLE_ALL = 1;
        static final int MSG_TYPE_TEXT = 1;
    }
}
