package io.supportflow.customer.wechat;

import io.supportflow.common.exception.BizException;
import io.supportflow.customer.dto.wechat.SaveCommand;
import io.supportflow.customer.dto.wechat.SendResult;
import io.supportflow.customer.dto.wechat.SimulateBody;
import io.supportflow.customer.dto.wechat.SubscribeLogVO;
import io.supportflow.customer.dto.wechat.SubscribeTestVO;
import io.supportflow.customer.dto.wechat.TemplateKind;
import io.supportflow.customer.dto.wechat.TemplateTestVO;
import io.supportflow.customer.dto.wechat.TestSubscribeBody;
import io.supportflow.customer.dto.wechat.TestTemplateBody;
import io.supportflow.customer.dto.wechat.WeChatDetailVO;
import io.supportflow.customer.dto.wechat.WeChatSaveVO;
import io.supportflow.customer.dto.wechat.WeChatSimulateVO;
import io.supportflow.customer.entity.SubscribeMessageLog;
import io.supportflow.customer.entity.WeChatChannelConfig;
import io.supportflow.customer.entity.WeChatCustomerBind;
import io.supportflow.customer.mapper.WeChatCustomerBindMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 公众号原生接入的后台业务：配置回显/保存、联调模拟、模板消息与订阅消息测试、发送日志。
 *
 * <p>这些逻辑原先堆在 {@code WeChatAdminController} 里（回显拼装、伪造进线 XML、
 * 模板类型分发、日志状态文案），都属于业务，控制器只该接参数、包响应。</p>
 */
@Service
public class WeChatAdminService {

    private static final Logger log = LoggerFactory.getLogger(WeChatAdminService.class);

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WeChatConfigService configService;
    private final WeChatInboundService inboundService;
    private final WeChatNotifyService notifyService;
    private final WeChatCustomerBindMapper bindMapper;
    /** 小程序订阅消息：测试发送 + 发送记录（与公众号模板消息是两套） */
    private final MiniAppSubscribeService subscribeService;

    public WeChatAdminService(WeChatConfigService configService, WeChatInboundService inboundService,
                              WeChatNotifyService notifyService, WeChatCustomerBindMapper bindMapper,
                              MiniAppSubscribeService subscribeService) {
        this.configService = configService;
        this.inboundService = inboundService;
        this.notifyService = notifyService;
        this.bindMapper = bindMapper;
        this.subscribeService = subscribeService;
    }

    /** 公众号渠道详情：配置回显 + 回调地址 + 各模板要填的字段。 */
    public WeChatDetailVO detail(String tenantCode, Long channelId) {
        WeChatChannelConfig config = configService.byChannel(tenantCode, channelId);
        // 密钥类字段只回显"是否已配置"，不回显明文：管理端页面被截图/录屏的概率比想象中高
        return new WeChatDetailVO(
                config != null,
                config == null ? null : config.getAppId(),
                config != null && config.getAppSecret() != null,
                config != null && config.getToken() != null,
                config != null && config.getAesKey() != null,
                config == null ? null : config.getWelcomeText(),
                config == null ? null : config.getTemplateLogistics(),
                config == null ? null : config.getTemplateTicket(),
                config == null ? null : config.getTemplateCoupon(),
                config == null ? null : config.getSubscribeTicket(),
                config == null ? null : config.getSubscribeService(),
                config == null ? null : config.getPlatform(),
                config == null ? null : configService.callbackUrl(config.getAppId()),
                config == null ? null : configService.oauthCallbackUrl(config.getAppId()),
                Map.of("LOGISTICS", WeChatNotifyService.fieldsOf(TemplateKind.LOGISTICS),
                        "TICKET", WeChatNotifyService.fieldsOf(TemplateKind.TICKET),
                        "COUPON", WeChatNotifyService.fieldsOf(TemplateKind.COUPON)));
    }

    /** 保存公众号配置（AppID / 密钥 / 令牌 / 模板 ID），保存后回带回调地址。 */
    public WeChatSaveVO save(String tenantCode, Long channelId,
                             SaveCommand command, String operator) {
        WeChatChannelConfig saved = configService.save(tenantCode, channelId, command, operator);
        log.info("公众号渠道配置已保存 tenant={} channelId={} appId={}",
                tenantCode, channelId, saved.getAppId());
        return new WeChatSaveVO(true, saved.getAppId(),
                configService.callbackUrl(saved.getAppId()),
                configService.oauthCallbackUrl(saved.getAppId()));
    }

    /**
     * 联调模拟器：伪造一条"微信推来的文本消息"，走完全一样的落库 + 机器人接待链路。
     *
     * <p>回复不会真的发到微信（openid 是 sim_ 前缀，出站推送会跳过），可以在会话记录里看到。</p>
     */
    public WeChatSimulateVO simulate(String tenantCode, Long channelId, SimulateBody body) {
        WeChatChannelConfig config = configService.byChannel(tenantCode, channelId);
        if (config == null) {
            throw new BizException(40001, "请先保存公众号配置再联调");
        }
        String content = body == null || body.content() == null || body.content().isBlank()
                ? "退货多久到账？" : body.content().trim();
        String openid = WeChatInboundService.SIMULATED_OPENID_PREFIX + "probe_" + System.currentTimeMillis();
        String xml = "<xml><ToUserName><![CDATA[" + config.getAppId() + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + openid + "]]></FromUserName>"
                + "<CreateTime>" + System.currentTimeMillis() / 1000 + "</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + content.replace("]]>", "]]&gt;") + "]]></Content>"
                + "<MsgId>sim-" + System.currentTimeMillis() + "</MsgId></xml>";
        inboundService.handle(config.getAppId(), xml, true);
        WeChatCustomerBind bind = bindMapper.selectOne(Wrappers.<WeChatCustomerBind>lambdaQuery()
                .eq(WeChatCustomerBind::getAppId, config.getAppId())
                .eq(WeChatCustomerBind::getOpenid, openid)
                .eq(WeChatCustomerBind::getDeleted, false)
                .last("LIMIT 1"));
        return new WeChatSimulateVO(bind == null ? null : bind.getSessionNo(),
                bind == null ? null : bind.getCustomerNo(), openid,
                "已按微信链路投递：消息已落库，机器人回复可在会话记录里查看（模拟 openid 不会真发到微信）");
    }

    /** 发一条模板消息测试（工单进展 / 物流 / 优惠券），验证配置对不对。 */
    public TemplateTestVO testTemplate(String tenantCode, Long channelId, TestTemplateBody body) {
        WeChatChannelConfig config = configService.byChannel(tenantCode, channelId);
        if (config == null) {
            throw new BizException(40001, "请先保存公众号配置");
        }
        WeChatCustomerBind bind = bindMapper.selectOne(Wrappers.<WeChatCustomerBind>lambdaQuery()
                .eq(WeChatCustomerBind::getAppId, config.getAppId())
                .eq(WeChatCustomerBind::getDeleted, false)
                .orderByDesc(WeChatCustomerBind::getId)
                .last("LIMIT 1"));
        if (bind == null) {
            throw new BizException(40001, "还没有任何关注者，先让一个微信用户关注公众号并说话");
        }
        String kind = body == null || body.kind() == null ? "TICKET" : body.kind().toUpperCase();
        boolean sent = switch (kind) {
            case "LOGISTICS" -> notifyService.sendLogistics(tenantCode, bind.getCustomerId(),
                    "OR202608281234", "运输中", "预计今日 18:00 前送达");
            case "COUPON" -> notifyService.sendCouponArrived(tenantCode, bind.getCustomerId(),
                    "延迟关怀券", "5.00 元", "2026-12-31");
            default -> notifyService.sendTicketProgress(tenantCode, bind.getCustomerId(),
                    "TK-260828-021", "发票抬头变更", "已受理", configService.callbackUrl(config.getAppId()));
        };
        return new TemplateTestVO(sent, bind.getOpenid(), kind);
    }

    /**
     * 小程序订阅消息：发一条测试消息（用最近绑定过的那个用户）。
     *
     * <p>为什么必须有这个按钮：小程序订阅消息的一次性订阅机制决定了"发不出去"极其常见
     * （客户没授权、次数用完、模板字段不符），只有在配置页能立刻试一次、并且能看到失败原因，
     * 企业才知道自己配的对不对。</p>
     */
    public SubscribeTestVO testSubscribe(String tenantCode, Long channelId, TestSubscribeBody body) {
        int scene = body == null || body.scene() == null ? MiniAppSubscribeService.SCENE_TICKET : body.scene();
        SendResult result = subscribeService.sendTest(tenantCode, channelId, scene);
        return new SubscribeTestVO(result.sent(), result.message(),
                subscribeService.recentLogs(tenantCode, 5));
    }

    /** 最近的订阅消息发送记录（页面展示"发了没、为什么没发"）。 */
    public List<SubscribeLogVO> subscribeLogs(String tenantCode, Integer limit) {
        List<SubscribeLogVO> rows = new ArrayList<>();
        for (SubscribeMessageLog row : subscribeService.recentLogs(tenantCode, limit)) {
            String statusText = switch (row.getStatus() == null ? 0 : row.getStatus()) {
                case 1 -> "已发送";
                case 2 -> "发送失败";
                default -> "已跳过";
            };
            rows.add(new SubscribeLogVO(String.valueOf(row.getId()), row.getScene(),
                    row.getScene() != null && row.getScene() == MiniAppSubscribeService.SCENE_TICKET
                            ? "工单进展" : "客服接入",
                    row.getBizNo(), row.getTemplateId(), row.getStatus(), statusText,
                    row.getErrcode(), row.getMessage(),
                    row.getCreateTime() == null ? null : row.getCreateTime().format(LOG_TIME)));
        }
        return rows;
    }
}
