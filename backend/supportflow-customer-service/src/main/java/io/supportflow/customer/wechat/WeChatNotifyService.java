package io.supportflow.customer.wechat;

import io.supportflow.customer.dto.wechat.TemplateKind;
import io.supportflow.customer.entity.WeChatChannelConfig;
import io.supportflow.customer.entity.WeChatCustomerBind;
import io.supportflow.customer.mapper.WeChatCustomerBindMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import io.supportflow.customer.service.ticket.TicketService;

/**
 * 模板消息：物流提醒 / 工单进展 / 优惠券到账。
 *
 * <p>模板消息是"服务通知"，只能发给关注了公众号且 openid 已知的客户；
 * 模板 ID 由企业在公众号后台申请后填到渠道配置里，这里只负责按模板拼数据并发送。
 * 三类模板的字段名（thing / time / character_string 等）是微信规定的，不能自己起名。</p>
 */
@Service
public class WeChatNotifyService {

    private static final Logger log = LoggerFactory.getLogger(WeChatNotifyService.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final WeChatCustomerBindMapper bindMapper;
    private final WeChatConfigService configService;
    private final WeChatAccessTokenService tokenService;
    private final WeChatApiClient apiClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public WeChatNotifyService(WeChatCustomerBindMapper bindMapper, WeChatConfigService configService,
                              WeChatAccessTokenService tokenService, WeChatApiClient apiClient) {
        this.bindMapper = bindMapper;
        this.configService = configService;
        this.tokenService = tokenService;
        this.apiClient = apiClient;
    }

    /** 物流提醒：包裹状态变化时发（由业务在物流事件里调用） */
    public boolean sendLogistics(String tenantCode, Long customerId, String orderNo, String status, String remark) {
        return send(tenantCode, customerId, TemplateKind.LOGISTICS, (config, payload) -> {
            payload.put("orderNo", orderNo);
            payload.put("status", status);
            payload.put("remark", remark);
        });
    }

    /** 工单进展：工单状态流转时发（TicketService 里已经挂上） */
    public boolean sendTicketProgress(String tenantCode, Long customerId, String ticketNo, String title,
                                      String status, String url) {
        return send(tenantCode, customerId, TemplateKind.TICKET, (config, payload) -> {
            payload.put("ticketNo", ticketNo);
            payload.put("title", title);
            payload.put("status", status);
            payload.put("time", LocalDateTime.now().format(TIME));
            payload.put("url", url);
        });
    }

    /** 优惠券到账：营销发放后调用 */
    public boolean sendCouponArrived(String tenantCode, Long customerId, String name, String amount,
                                     String expireAt) {
        return send(tenantCode, customerId, TemplateKind.COUPON, (config, payload) -> {
            payload.put("name", name);
            payload.put("amount", amount);
            payload.put("expireAt", expireAt);
        });
    }

    private boolean send(String tenantCode, Long customerId, TemplateKind kind, PayloadBuilder builder) {
        if (customerId == null) {
            return false;
        }
        WeChatCustomerBind bind = bindMapper.selectOne(Wrappers.<WeChatCustomerBind>lambdaQuery()
                .eq(WeChatCustomerBind::getTenantCode, tenantCode)
                .eq(WeChatCustomerBind::getCustomerId, customerId)
                .eq(WeChatCustomerBind::getDeleted, false)
                .orderByDesc(WeChatCustomerBind::getId)
                .last("LIMIT 1"));
        if (bind == null) {
            return false;
        }
        if (bind.getOpenid() != null && bind.getOpenid().startsWith(WeChatInboundService.SIMULATED_OPENID_PREFIX)) {
            log.info("模拟会话不发模板消息 tenant={} customerId={}", tenantCode, customerId);
            return false;
        }
        WeChatChannelConfig config = configService.byAppId(bind.getAppId());
        String templateId = config == null ? null : templateIdOf(kind, config);
        if (config == null || templateId == null || templateId.isBlank()) {
            log.debug("公众号没配这个模板消息，跳过 kind={} tenant={}", kind, tenantCode);
            return false;
        }
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("touser", bind.getOpenid());
            payload.put("template_id", templateId);
            ObjectNode data = payload.putObject("data");
            ObjectNode values = mapper.createObjectNode();
            builder.fill(config, values);
            // 微信要求 data 是 { "字段名": { "value": "..." } } 的结构
            values.fieldNames().forEachRemaining(field ->
                    data.putObject(field).put("value", values.path(field).asText("")));
            apiClient.sendTemplate(tokenService.token(config), payload);
            log.info("模板消息已发送 kind={} tenant={} customerId={} templateId={}",
                    kind, tenantCode, customerId, templateId);
            return true;
        } catch (Exception e) {
            log.warn("模板消息发送失败 kind={} tenant={} customerId={} error={}",
                    kind, tenantCode, customerId, e.getMessage());
            return false;
        }
    }

    interface PayloadBuilder {
        void fill(WeChatChannelConfig config, ObjectNode values);
    }

    /**
     * 模板类型 → 渠道配置里对应的模板 ID。
     *
     * <p>映射放业务侧（而不是塞进 dto 的枚举里）：dto 不该反过来依赖渠道配置实体。</p>
     */
    private static String templateIdOf(TemplateKind kind, WeChatChannelConfig config) {
        return switch (kind) {
            case LOGISTICS -> config.getTemplateLogistics();
            case TICKET -> config.getTemplateTicket();
            case COUPON -> config.getTemplateCoupon();
        };
    }

    /** 模板消息字段名（微信官方模板字段，企业申请模板时按这个选） */
    public static List<String> fieldsOf(TemplateKind kind) {
        return switch (kind) {
            case LOGISTICS -> List.of("character_string1", "phrase2", "thing3");
            case TICKET -> List.of("character_string1", "thing2", "phrase3", "time4");
            case COUPON -> List.of("thing1", "amount2", "time3");
        };
    }
}
