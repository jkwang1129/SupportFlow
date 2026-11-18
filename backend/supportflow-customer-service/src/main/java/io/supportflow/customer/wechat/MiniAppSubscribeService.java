package io.supportflow.customer.wechat;

import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.customer.dto.wechat.SendResult;
import io.supportflow.customer.entity.SubscribeMessageLog;
import io.supportflow.customer.entity.WeChatChannelConfig;
import io.supportflow.customer.entity.WeChatCustomerBind;
import io.supportflow.customer.mapper.SubscribeMessageLogMapper;
import io.supportflow.customer.mapper.WeChatCustomerBindMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 小程序「订阅消息」：工单进展 / 客服接入。
 *
 * <p>与公众号模板消息（{@link WeChatNotifyService}）并列，但三处关键差别：</p>
 *
 * <ol>
 *   <li><b>模板 ID 不是一套</b>：小程序订阅模板要在小程序后台申请，存在
 *       {@code channel_wechat_config.subscribe_ticket / subscribe_service}；</li>
 *   <li><b>一次性订阅</b>：用户点一次授权才能发一次，所以"没发出去"多半不是 bug，
 *       而是没订阅——这件事必须让企业看得见，于是每次尝试都写
 *       {@code subscribe_message_log}；</li>
 *   <li><b>同一单同一场景只发一次</b>：靠日志表上的部分唯一索引（status=1）兜底，
 *       重复点"流转"不会再打扰客户。</li>
 * </ol>
 *
 * <p>三条硬约定：**没配模板就跳过**（不是失败，配置页会提示"待配置"）、
 * **模拟身份不真发**（自检造的 sim_ openid）、**任何异常都不往上抛**——通知失败不该把
 * 工单流转/坐席接入整个事务弄挂。</p>
 */
@Service
public class MiniAppSubscribeService {

    /** 场景码（与 subscribe_message_log.scene 一致） */
    public static final int SCENE_TICKET = 1;
    public static final int SCENE_SERVICE = 2;

    /** 日志结果码 */
    private static final short STATUS_SENT = 1;
    private static final short STATUS_FAILED = 2;
    private static final short STATUS_SKIPPED = 3;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Logger log = LoggerFactory.getLogger(MiniAppSubscribeService.class);

    private final WeChatConfigService configService;
    private final WeChatCustomerBindMapper bindMapper;
    private final SubscribeMessageLogMapper logMapper;
    private final WeChatAccessTokenService tokenService;
    private final WeChatApiClient apiClient;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper mapper = new ObjectMapper();

    public MiniAppSubscribeService(WeChatConfigService configService,
                                   WeChatCustomerBindMapper bindMapper,
                                   SubscribeMessageLogMapper logMapper,
                                   WeChatAccessTokenService tokenService,
                                   WeChatApiClient apiClient,
                                   SnowflakeIdGenerator idGenerator) {
        this.configService = configService;
        this.bindMapper = bindMapper;
        this.logMapper = logMapper;
        this.tokenService = tokenService;
        this.apiClient = apiClient;
        this.idGenerator = idGenerator;
    }

    /**
     * 工单进展：工单状态流转时发（建单 / 受理 / 待客户确认 / 已解决 / 已关闭）。
     *
     * @param bizNo 幂等用的业务号，传工单号
     */
    public boolean sendTicketProgress(String tenantCode, Long customerId, String ticketNo,
                                      String title, String status) {
        ObjectNode data = mapper.createObjectNode();
        put(data, "character_string1", ticketNo);
        put(data, "thing2", shorten(title, 20));
        put(data, "phrase3", status);
        put(data, "time4", LocalDateTime.now().format(TIME));
        return send(tenantCode, customerId, SCENE_TICKET, ticketNo,
                "pages/service/index?ticketNo=" + ticketNo, data);
    }

    /**
     * 客服接入：坐席刚接手会话时发（客户离开小程序也能收到"客服已接入"）。
     *
     * @param bizNo 幂等用的业务号，传会话号
     */
    public boolean sendAgentOnline(String tenantCode, Long customerId, String sessionNo, String agentName) {
        ObjectNode data = mapper.createObjectNode();
        put(data, "thing1", shorten(agentName == null ? "人工客服" : agentName, 20));
        put(data, "time2", LocalDateTime.now().format(TIME));
        put(data, "thing3", shorten("您的会话已由人工客服接手，可以直接提问", 20));
        return send(tenantCode, customerId, SCENE_SERVICE, sessionNo,
                "pages/service/index?sessionNo=" + sessionNo, data);
    }

    /** 配置页的「发送测试消息」：用最近一个绑定的客户真发一条 */
    public SendResult sendTest(String tenantCode, Long channelId, int scene) {
        WeChatChannelConfig config = configService.byChannel(tenantCode, channelId);
        if (config == null) {
            return new SendResult(false, "请先保存小程序配置（AppID / AppSecret / 订阅模板 ID）");
        }
        WeChatCustomerBind bind = bindMapper.selectOne(Wrappers.<WeChatCustomerBind>lambdaQuery()
                .eq(WeChatCustomerBind::getAppId, config.getAppId())
                .eq(WeChatCustomerBind::getDeleted, false)
                .orderByDesc(WeChatCustomerBind::getId)
                .last("LIMIT 1"));
        if (bind == null) {
            return new SendResult(false, "还没有绑定过的小程序用户，先让一个用户在小程序里点「在线客服」并说话");
        }
        boolean sent = scene == SCENE_SERVICE
                ? sendAgentOnline(tenantCode, bind.getCustomerId(), "S-TEST-" + System.currentTimeMillis(), "小云")
                : sendTicketProgress(tenantCode, bind.getCustomerId(),
                        "TK-TEST-" + System.currentTimeMillis(), "发票抬头变更", "已受理");
        return new SendResult(sent, sent ? "测试消息已发送，请在微信里查看"
                : "发送未成功，到「发送记录」里看具体原因（多半是客户没有订阅该模板）");
    }

    /** 最近发送记录（配置页展示：发了没、为什么没发） */
    public List<SubscribeMessageLog> recentLogs(String tenantCode, int limit) {
        return logMapper.selectList(Wrappers.<SubscribeMessageLog>lambdaQuery()
                .eq(SubscribeMessageLog::getTenantCode, tenantCode)
                .orderByDesc(SubscribeMessageLog::getCreateTime)
                .orderByDesc(SubscribeMessageLog::getId)
                .last("LIMIT " + Math.min(Math.max(1, limit), 50)));
    }

    // ------------------------------------------------------------------ 内部

    private boolean send(String tenantCode, Long customerId, int scene, String bizNo,
                         String page, ObjectNode data) {
        if (tenantCode == null || customerId == null) {
            return false;
        }
        WeChatCustomerBind bind = bindMiniapp(tenantCode, customerId);
        if (bind == null) {
            writeLog(tenantCode, null, null, null, customerId, scene, null, bizNo,
                    STATUS_SKIPPED, null, "这个客户没有绑定小程序身份（openid），跳过", null);
            return false;
        }
        if (bind.getOpenid() != null
                && bind.getOpenid().startsWith(WeChatInboundService.SIMULATED_OPENID_PREFIX)) {
            writeLog(tenantCode, channelIdOf(bind), bind.getAppId(), bind.getOpenid(), customerId,
                    scene, null, bizNo, STATUS_SKIPPED, null, "模拟身份（自检造的），不发真实消息", null);
            return false;
        }
        WeChatChannelConfig config = configService.byAppId(bind.getAppId());
        String templateId = config == null ? null : templateIdOf(config, scene);
        if (config == null || templateId == null || templateId.isBlank()) {
            writeLog(tenantCode, channelIdOf(bind), bind.getAppId(), bind.getOpenid(), customerId,
                    scene, templateId, bizNo, STATUS_SKIPPED, null,
                    scene == SCENE_TICKET ? "小程序没配「工单进展」订阅模板 ID，跳过"
                            : "小程序没配「客服接入」订阅模板 ID，跳过", null);
            return false;
        }
        // 幂等要在**发送之前**判：订阅消息是一次性的，重复发不但没意义，还会把客户的
        // 订阅次数用掉。唯一索引只是并发兜底（两个请求同时进来），不能当作唯一的防线。
        if (alreadySent(tenantCode, scene, bizNo)) {
            log.info("小程序订阅消息已发过，本轮不重复打扰 tenant={} scene={} bizNo={}",
                    tenantCode, scene, bizNo);
            return false;
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("touser", bind.getOpenid());
        payload.put("template_id", templateId);
        payload.put("page", page);
        payload.set("data", data);
        try {
            apiClient.sendSubscribe(tokenService.token(config), bind.getOpenid(), templateId, page, data);
            writeLog(tenantCode, channelIdOf(bind), bind.getAppId(), bind.getOpenid(), customerId,
                    scene, templateId, bizNo, STATUS_SENT, 0, "已发送", payload.toString());
            log.info("小程序订阅消息已发送 tenant={} scene={} bizNo={} templateId={}",
                    tenantCode, scene, bizNo, templateId);
            return true;
        } catch (WeChatApiException e) {
            // 微信明确报错：把 errcode 与"该怎么办"一起记下来（43101 没订阅最常见）
            writeLog(tenantCode, channelIdOf(bind), bind.getAppId(), bind.getOpenid(), customerId,
                    scene, templateId, bizNo, STATUS_FAILED, e.getErrcode(), e.getMessage(),
                    payload.toString());
            log.warn("小程序订阅消息发送失败 tenant={} scene={} bizNo={} error={}",
                    tenantCode, scene, bizNo, e.getMessage());
            return false;
        } catch (Exception e) {
            writeLog(tenantCode, channelIdOf(bind), bind.getAppId(), bind.getOpenid(), customerId,
                    scene, templateId, bizNo, STATUS_FAILED, null, brief(e.getMessage()),
                    payload.toString());
            log.warn("小程序订阅消息发送异常 tenant={} scene={} bizNo={} error={}",
                    tenantCode, scene, bizNo, e.getMessage());
            return false;
        }
    }

    /** 找这个客户的**小程序**绑定（platform=2 的渠道配置对应的 appId） */
    private WeChatCustomerBind bindMiniapp(String tenantCode, Long customerId) {
        for (WeChatChannelConfig config : configService.miniappConfigs(tenantCode)) {
            WeChatCustomerBind bind = bindMapper.selectOne(Wrappers.<WeChatCustomerBind>lambdaQuery()
                    .eq(WeChatCustomerBind::getAppId, config.getAppId())
                    .eq(WeChatCustomerBind::getCustomerId, customerId)
                    .eq(WeChatCustomerBind::getDeleted, false)
                    .orderByDesc(WeChatCustomerBind::getId)
                    .last("LIMIT 1"));
            if (bind != null) {
                return bind;
            }
        }
        return null;
    }

    /** 绑定的渠道 ID：wechat_customer_bind 上没有这一列，按 appId 反查渠道配置拿（回调路由本来也是按 appId） */
    private Long channelIdOf(WeChatCustomerBind bind) {
        WeChatChannelConfig config = configService.byAppId(bind.getAppId());
        return config == null ? null : config.getChannelId();
    }

    private static String templateIdOf(WeChatChannelConfig config, int scene) {
        return scene == SCENE_TICKET ? config.getSubscribeTicket() : config.getSubscribeService();
    }

    /**
     * 这条业务号在这个场景下是否**已经成功发过**。
     *
     * <p>只认成功（status=1）的那条：失败与跳过都不算——客户重新订阅之后应该还能再发。</p>
     */
    private boolean alreadySent(String tenantCode, int scene, String bizNo) {
        if (bizNo == null || bizNo.isBlank()) {
            return false;
        }
        Long count = logMapper.selectCount(Wrappers.<SubscribeMessageLog>lambdaQuery()
                .eq(SubscribeMessageLog::getTenantCode, tenantCode)
                .eq(SubscribeMessageLog::getScene, (short) scene)
                .eq(SubscribeMessageLog::getBizNo, bizNo)
                .eq(SubscribeMessageLog::getStatus, STATUS_SENT));
        return count != null && count > 0;
    }

    /**
     * 记一条发送日志。
     *
     * <p>日志写入失败只打 warn：**通知是旁路**，不该因为日志表写不进去而把工单流转/坐席接入弄挂。</p>
     */
    private void writeLog(String tenantCode, Long channelId, String appId, String openid,
                         Long customerId, int scene, String templateId, String bizNo,
                         short status, Integer errcode, String message, String payloadJson) {
        try {
            logMapper.insert(SubscribeMessageLog.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenantCode)
                    .channelId(channelId)
                    .appId(appId)
                    .openid(openid)
                    .customerId(customerId)
                    .scene((short) scene)
                    .templateId(templateId)
                    .bizNo(bizNo)
                    .status(status)
                    .errcode(errcode)
                    .message(brief(message))
                    .payloadJson(payloadJson)
                    .createTime(LocalDateTime.now())
                    .build());
        } catch (DuplicateKeyException e) {
            // 同一单同一场景已经成功发过一次（部分唯一索引拦住），这正是想要的幂等
            log.info("小程序订阅消息已发过，跳过重复 tenant={} scene={} bizNo={}",
                    tenantCode, scene, bizNo);
        } catch (Exception e) {
            log.warn("写订阅消息日志失败 tenant={} scene={} bizNo={} error={}",
                    tenantCode, scene, bizNo, e.getMessage());
        }
    }

    private static void put(ObjectNode data, String field, String value) {
        data.putObject(field).put("value", value == null ? "—" : value);
    }

    /** 微信对 thing 类字段有长度上限（20 个字符），超了直接报 47003，这里先截断 */
    private static String shorten(String text, int max) {
        if (text == null) {
            return "—";
        }
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1) + "…";
    }

    private static String brief(String text) {
        if (text == null) {
            return null;
        }
        String one = text.replaceAll("\\s+", " ").trim();
        return one.length() > 480 ? one.substring(0, 480) : one;
    }

    }
