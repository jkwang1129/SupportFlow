package io.supportflow.customer.wechat;

import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.customer.dto.session.OpenCommand;
import io.supportflow.customer.dto.session.OpenResult;
import io.supportflow.customer.entity.ChannelKey;
import io.supportflow.customer.entity.Customer;
import io.supportflow.customer.entity.WeChatChannelConfig;
import io.supportflow.customer.entity.WeChatCustomerBind;
import io.supportflow.customer.entity.WeChatInboundLog;
import io.supportflow.customer.mapper.ChannelKeyMapper;
import io.supportflow.customer.mapper.CustomerMapper;
import io.supportflow.customer.mapper.WeChatCustomerBindMapper;
import io.supportflow.customer.mapper.WeChatInboundLogMapper;
import io.supportflow.customer.service.session.SessionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公众号消息进线：微信推过来的消息 → 客户档案 → 会话 → 落库 → 交给机器人/坐席。
 *
 * <p>关键设计：</p>
 * <ol>
 *   <li><b>5 秒内必须回 success</b>：微信只等 5 秒，超时会重试。所以这里只做"落库 + 触发"，
 *       真正的回复走客服消息接口异步推（{@link WeChatOutboundNotifier}）；</li>
 *   <li><b>幂等</b>：按 MsgId 去重，微信重推不会让客户一句话出现两遍；</li>
 *   <li><b>身份复用</b>：openid 绑定客户档案，同一个人再来还是同一条轨迹；</li>
 *   <li><b>模拟器共用同一条链路</b>：{@code simulated=true} 时不调微信接口，
 *       但走完全一样的落库与机器人接待，联调不必等公众号审核。</li>
 * </ol>
 */
@Service
public class WeChatInboundService {

    private static final Logger log = LoggerFactory.getLogger(WeChatInboundService.class);
    /** 模拟器用的 openid 前缀：出站推送看到它就跳过（不然会去调微信接口） */
    public static final String SIMULATED_OPENID_PREFIX = "sim_";

    private final WeChatConfigService configService;
    private final WeChatInboundLogMapper inboundLogMapper;
    private final WeChatCustomerBindMapper bindMapper;
    private final CustomerMapper customerMapper;
    private final ChannelKeyMapper channelKeyMapper;
    private final SessionService sessionService;
    private final SnowflakeIdGenerator idGenerator;
    private final WeChatAccessTokenService tokenService;
    private final WeChatApiClient apiClient;

    public WeChatInboundService(WeChatConfigService configService,
                                WeChatInboundLogMapper inboundLogMapper,
                                WeChatCustomerBindMapper bindMapper,
                                CustomerMapper customerMapper,
                                ChannelKeyMapper channelKeyMapper,
                                SessionService sessionService,
                                SnowflakeIdGenerator idGenerator,
                                WeChatAccessTokenService tokenService,
                                WeChatApiClient apiClient) {
        this.configService = configService;
        this.inboundLogMapper = inboundLogMapper;
        this.bindMapper = bindMapper;
        this.customerMapper = customerMapper;
        this.channelKeyMapper = channelKeyMapper;
        this.sessionService = sessionService;
        this.idGenerator = idGenerator;
        this.tokenService = tokenService;
        this.apiClient = apiClient;
    }

    /** 处理一条明文 XML 消息（公众号；控制器负责验签与解密），返回给微信的响应体 */
    public String handle(String appId, String plainXml, boolean simulated) {
        return dispatch(appId, WeChatXml.parse(plainXml), simulated);
    }

    /**
     * 处理一条小程序客服消息（JSON，字段名与公众号 XML 一致：ToUserName / FromUserName / MsgType / Content…）。
     *
     * <p>微信小程序的客服消息推送是 JSON、公众号是 XML，其余处理链路完全一样，
     * 所以这里只把 JSON 折成同一张 Map，交给同一个 {@link #dispatch}。</p>
     */
    public String handleJson(String appId, String json, boolean simulated) {
        try {
            JsonNode node =
                    new ObjectMapper().readTree(json);
            Map<String, String> message = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry ->
                    message.put(entry.getKey(), entry.getValue().asText()));
            return dispatch(appId, message, simulated);
        } catch (Exception e) {
            log.warn("小程序客服消息解析失败 appId={} error={}", appId, e.getMessage());
            return "success";
        }
    }

    private String dispatch(String appId, Map<String, String> message, boolean simulated) {
        WeChatChannelConfig config = configService.byAppId(appId);
        if (config == null) {
            log.warn("收到未配置的公众号消息，已忽略 appId={}", appId);
            return "success";
        }
        String openid = message.get("FromUserName");
        String msgType = value(message.get("MsgType"));
        String event = message.get("Event");
        String dedupeKey = dedupeKey(message, msgType, event);
        if (!simulated && !markProcessed(config, dedupeKey, openid, msgType, event)) {
            log.info("微信消息重复推送，已跳过 appId={} msgId={}", appId, dedupeKey);
            return "success";
        }
        switch (msgType) {
            case "text" -> handleText(config, openid, value(message.get("Content")), simulated);
            case "event" -> handleEvent(config, openid, value(event), simulated);
            default -> push(config, openid, "目前支持文字咨询，图片和语音请稍后再试～", simulated);
        }
        return "success";
    }

    private void handleText(WeChatChannelConfig config, String openid, String content, boolean simulated) {
        if (content.isBlank()) {
            return;
        }
        WeChatCustomerBind bind = bindOrCreate(config, openid, simulated);
        // 走核心链路：落库 → 实时质检 → 客服大脑（机器人接一轮）→ 事件广播（外部渠道推送）
        sessionService.appendMessage(config.getTenantCode(), bind.getSessionNo(), SessionService.SENDER_CUSTOMER,
                null, SessionService.MSG_TYPE_TEXT, content, SessionService.VISIBLE_ALL, null);
        log.info("公众号消息已进线 tenant={} appId={} openid={} 内容={}",
                config.getTenantCode(), config.getAppId(), openid, preview(content));
    }

    private void handleEvent(WeChatChannelConfig config, String openid, String event, boolean simulated) {
        switch (event) {
            // subscribe：公众号关注；user_enter_tempsession：小程序客户进入客服会话——两者都该发欢迎语
            case "subscribe", "user_enter_tempsession" -> {
                WeChatCustomerBind bind = bindOrCreate(config, openid, simulated);
                push(config, openid, config.getWelcomeText() == null || config.getWelcomeText().isBlank()
                        ? "您好，欢迎关注！有什么可以帮您？直接在这里发消息就行。"
                        : config.getWelcomeText(), simulated);
                log.info("公众号新关注 tenant={} openid={} customerNo={}",
                        config.getTenantCode(), openid, bind.getCustomerNo());
            }
            case "unsubscribe" -> {
                WeChatCustomerBind bind = bindMapper.selectOne(Wrappers.<WeChatCustomerBind>lambdaQuery()
                        .eq(WeChatCustomerBind::getAppId, config.getAppId())
                        .eq(WeChatCustomerBind::getOpenid, openid)
                        .eq(WeChatCustomerBind::getDeleted, false)
                        .last("LIMIT 1"));
                if (bind != null) {
                    bind.setSubscribeStatus(2);
                    bind.setUpdateTime(LocalDateTime.now());
                    bindMapper.updateById(bind);
                }
                log.info("公众号取消关注 appId={} openid={}", config.getAppId(), openid);
            }
            default -> log.debug("忽略公众号事件 appId={} event={}", config.getAppId(), event);
        }
    }

    /** openid → 客户档案：没有就建（复用开会话链路，客户编号/访客令牌都由核心服务签发） */
    public WeChatCustomerBind bindOrCreate(WeChatChannelConfig config, String openid, boolean simulated) {
        WeChatCustomerBind exists = bindMapper.selectOne(Wrappers.<WeChatCustomerBind>lambdaQuery()
                .eq(WeChatCustomerBind::getAppId, config.getAppId())
                .eq(WeChatCustomerBind::getOpenid, openid)
                .eq(WeChatCustomerBind::getDeleted, false)
                .last("LIMIT 1"));
        if (exists != null && exists.getSessionNo() != null && !exists.getSessionNo().isBlank()) {
            return exists;
        }
        String appKey = appKeyOf(config.getChannelId());
        OpenResult opened = sessionService.openSession(new OpenCommand(
                appKey, null, "微信访客", null));
        Customer customer = customerMapper.selectOne(Wrappers.<Customer>lambdaQuery()
                .eq(Customer::getTenantCode, config.getTenantCode())
                .eq(Customer::getCustomerNo, opened.customerNo())
                .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (exists == null) {
            exists = WeChatCustomerBind.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(config.getTenantCode())
                    .appId(config.getAppId())
                    .openid(openid)
                    .customerId(customer == null ? null : customer.getId())
                    .customerNo(opened.customerNo())
                    .customerName(opened.customerName())
                    .sessionNo(opened.sessionNo())
                    .subscribeStatus(1)
                    .bindTime(now)
                    .createTime(now)
                    .updateTime(now)
                    .deleted(false)
                    .build();
            bindMapper.insert(exists);
        } else {
            exists.setSessionNo(opened.sessionNo());
            exists.setUpdateTime(now);
            bindMapper.updateById(exists);
        }
        return exists;
    }

    /** 渠道 AppID：开会话接口是按渠道密钥认渠道的 */
    private String appKeyOf(Long channelId) {
        ChannelKey key = channelKeyMapper.selectOne(Wrappers.<ChannelKey>lambdaQuery()
                .eq(ChannelKey::getChannelId, channelId)
                .eq(ChannelKey::getStatus, 1)
                .eq(ChannelKey::getDeleted, false)
                .orderByDesc(ChannelKey::getId)
                .last("LIMIT 1"));
        if (key == null) {
            throw new BizException(40401, "这个公众号渠道还没有可用密钥，请先在渠道里生成密钥");
        }
        return key.getAppKey();
    }

    /** 幂等：写一条去重日志，撞唯一索引说明重复推送 */
    @Transactional
    public boolean markProcessed(WeChatChannelConfig config, String dedupeKey, String openid,
                                 String msgType, String event) {
        try {
            inboundLogMapper.insert(WeChatInboundLog.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(config.getTenantCode())
                    .appId(config.getAppId())
                    .msgId(dedupeKey)
                    .openid(openid)
                    .msgType(msgType)
                    .eventType(event)
                    .createTime(LocalDateTime.now())
                    .deleted(false)
                    .build());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 主动推一条客服消息（关注欢迎语、非文本提示等） */
    private void push(WeChatChannelConfig config, String openid, String content, boolean simulated) {
        if (simulated || openid == null || openid.startsWith(SIMULATED_OPENID_PREFIX)) {
            log.info("模拟推送（不调微信）appId={} content={}", config.getAppId(), preview(content));
            return;
        }
        try {
            apiClient.sendCustomText(tokenService.token(config), openid, content);
        } catch (Exception e) {
            log.warn("微信客服消息推送失败 appId={} openid={} error={}", config.getAppId(), openid, e.getMessage());
        }
    }

    private static String dedupeKey(Map<String, String> message, String msgType, String event) {
        String msgId = message.get("MsgId");
        if (msgId != null && !msgId.isBlank()) {
            return msgId;
        }
        return msgType + ":" + value(event) + ":" + value(message.get("CreateTime")) + ":"
                + value(message.get("FromUserName"));
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 60 ? text.substring(0, 60) + "…" : text;
    }
}
