package io.supportflow.customer.nativechannel;

import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.customer.dto.channel.NativeField;
import io.supportflow.customer.entity.Channel;
import io.supportflow.customer.entity.ChannelKey;
import io.supportflow.customer.entity.ChannelNativeConfig;
import io.supportflow.customer.mapper.ChannelKeyMapper;
import io.supportflow.customer.mapper.ChannelMapper;
import io.supportflow.customer.mapper.ChannelNativeConfigMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原生渠道配置（App / 400 热线 / 邮件）。
 *
 * <p>三类渠道的配置项差别很大，所以用「服务端给字段定义 + JSON 存值」的方式：
 * 前端一个页面就能渲染三种表单，以后加渠道只加一段 schema。
 * 密钥类字段（secret=true）永远不回显明文，只回"是否已配置"。</p>
 */
@Service
public class ChannelNativeConfigService {

    private static final Logger log = LoggerFactory.getLogger(ChannelNativeConfigService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 配置字段定义：新增渠道类型时只加一段。
     *
     * <p>字段类型本身在 {@link NativeField}（dto 包）——它是接口报文的一部分，
     * 不该由服务内部类型充当。</p>
     */
    private static final Map<Integer, List<NativeField>> SCHEMA = Map.of(
            4, List.of(
                    new NativeField("welcomeText", "欢迎语", "textarea", false, false,
                            "您好，这里是客服中心，请描述您的问题～", "App 打开客服页时展示，留空用默认文案"),
                    new NativeField("pushWebhook", "离线推送地址", "text", false, false,
                            "https://你的推送服务/supportflow/notify", "坐席/机器人回复时平台会 POST 到这里，由它下发 APNs / FCM"),
                    new NativeField("pushSecret", "推送签名密钥", "secret", false, true,
                            "", "平台用它对推送请求签名（X-SupportFlow-Signature / X-SupportFlow-Timestamp）"),
                    new NativeField("allowImage", "允许发图片", "text", false, false, "1", "1-允许 0-不允许")
            ),
            5, List.of(
                    new NativeField("webhookSecret", "呼叫中心签名密钥", "secret", true, true,
                            "", "呼叫中心回调平台时用它签名"),
                    new NativeField("ivrMenu", "语音导航菜单", "json", true, false,
                            "[{\"key\":\"1\",\"label\":\"订单查询\",\"action\":\"bot\"},{\"key\":\"2\",\"label\":\"售后服务\",\"action\":\"bot\"},{\"key\":\"0\",\"label\":\"人工服务\",\"action\":\"agent\"}]",
                            "按键 → 动作：bot 机器人回答 / agent 转人工；平台会把播报文案回给呼叫中心"),
                    new NativeField("defaultSkillGroupId", "默认技能组", "text", false, false,
                            "", "转人工时优先派这个技能组（留空按渠道绑定的技能组走）"),
                    new NativeField("summaryTemplate", "通话小结模板", "textarea", false, false,
                            "客户诉求：{intent}\n处理结果：{action}\n通话时长：{duration}",
                            "挂机后自动生成小结并建工单，{intent}/{action}/{duration} 会被替换")
            ),
            6, List.of(
                    // 邮件渠道只支持方案 A：平台直连企业邮箱（IMAP 收信 + SMTP 发信），
                    // 每个渠道配自己的邮箱，租户之间互不影响。这里不再有"收信/发信方式"的选择项。
                    new NativeField("imapHost", "IMAP 服务器", "text", true, false,
                            "imap.exmail.qq.com", "企业客服邮箱的 IMAP 地址，必须支持 IMAPS/SSL（默认端口 993）"),
                    new NativeField("imapPort", "IMAP 端口", "text", false, false, "993", "默认 993"),
                    new NativeField("imapUsername", "IMAP 账号", "text", true, false,
                            "kefu@yourcompany.com", "客服邮箱完整地址；平台用这个账号登录、拉未读邮件自动建工单"),
                    new NativeField("imapPassword", "IMAP 密码 / 授权码", "secret", true, true,
                            "邮箱授权码", "多数邮箱要填「授权码」而不是登录密码；保存后不回显，留空表示不修改"),
                    new NativeField("imapFolder", "收件文件夹", "text", false, false,
                            "INBOX", "默认 INBOX；如果来信规则会把邮件挪到子文件夹，这里填那个文件夹名"),
                    new NativeField("imapRecipient", "只处理发到这个地址的信", "text", false, false,
                            "", "留空=只收发给 IMAP 账号的邮件；邮箱同时收别的信时用它过滤"),
                    new NativeField("imapSince", "只处理这个时间之后的邮件", "text", false, false,
                            "2026-10-03 00:00", "格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm；首次接入建议填今天，"
                            + "避免把邮箱里已有的历史未读邮件都建成工单。留空=处理所有未读"),
                    new NativeField("smtpHost", "SMTP 服务器", "text", false, false,
                            "smtp.exmail.qq.com", "企业客服邮箱的 SMTP 地址（默认端口 465）。不填则不发受理回执 / 回复 / 回访邮件"),
                    new NativeField("smtpPort", "SMTP 端口", "text", false, false, "465", "默认 465"),
                    new NativeField("smtpUsername", "SMTP 账号", "text", false, false,
                            "kefu@yourcompany.com", "一般与 IMAP 账号相同"),
                    new NativeField("smtpPassword", "SMTP 密码 / 授权码", "secret", false, true,
                            "邮箱授权码", "保存后不回显，留空表示不修改"),
                    new NativeField("smtpFromName", "发件人显示名", "text", false, false,
                            "SupportFlow 智能客服", "客户收到邮件时看到的发件人名称"),
                    new NativeField("autoReplyTemplate", "自动受理回执", "textarea", false, false,
                            "您好，您的邮件已受理，工单号 {ticketNo}，我们会在 1 个工作日内处理并回复。",
                            "收到邮件后立刻用上面的 SMTP 回执，{ticketNo} 会被替换成真实工单号"),
                    new NativeField("satisfactionTemplate", "解决后回访模板", "textarea", false, false,
                            "您好，您在工单 {ticketNo} 中反馈的问题已经处理完成。\n麻烦花 10 秒评价一下本次服务（点开下面链接，选 1~5 分即可）：\n{link}",
                            "工单转为「已解决」后自动发这封回访邮件；{ticketNo} 工单号、{link} 带签名的评价链接（客户点开即可打分，无需登录），留空用默认文案"),
                    new NativeField("defaultPriority", "默认优先级", "text", false, false, "2", "1-紧急 2-高 3-中 4-低"),
                    new NativeField("defaultSkillGroupId", "默认处理技能组", "text", false, false, "", "建单时派给这个技能组")
            )
    );

    private final ChannelNativeConfigMapper configMapper;
    private final ChannelMapper channelMapper;
    private final ChannelKeyMapper channelKeyMapper;
    private final SnowflakeIdGenerator idGenerator;

    @Value("${supportflow.native.callback-base-url:}")
    private String callbackBaseUrl;

    public ChannelNativeConfigService(ChannelNativeConfigMapper configMapper, ChannelMapper channelMapper,
                                      ChannelKeyMapper channelKeyMapper, SnowflakeIdGenerator idGenerator) {
        this.configMapper = configMapper;
        this.channelMapper = channelMapper;
        this.channelKeyMapper = channelKeyMapper;
        this.idGenerator = idGenerator;
    }

    public List<NativeField> fields(int channelType) {
        return SCHEMA.getOrDefault(channelType, List.of());
    }

    public ChannelNativeConfig row(String tenantCode, Long channelId) {
        return configMapper.selectOne(Wrappers.<ChannelNativeConfig>lambdaQuery()
                .eq(ChannelNativeConfig::getTenantCode, tenantCode)
                .eq(ChannelNativeConfig::getChannelId, channelId)
                .eq(ChannelNativeConfig::getDeleted, false)
                .last("LIMIT 1"));
    }

    /** 原始配置（服务内部用，含密钥明文） */
    public Map<String, Object> raw(String tenantCode, Long channelId) {
        ChannelNativeConfig row = row(tenantCode, channelId);
        return row == null ? Map.of() : parse(row.getConfigJson());
    }

    /** 给页面用的值：密钥字段只回「是否已配置」 */
    public Map<String, Object> displayValues(String tenantCode, Long channelId) {
        Channel row = channelMapper.selectById(channelId);
        Map<String, Object> values = new LinkedHashMap<>(raw(tenantCode, channelId));
        for (NativeField field : fields(row == null ? 0 : row.getChannelType())) {
            if (field.secret()) {
                Object current = values.remove(field.name());
                values.put(field.name() + "Configured", current != null && !String.valueOf(current).isBlank());
            }
        }
        // 邮件渠道历史上存过网关方案的密钥（收信/发信签名密钥）。现在只配方案 A，这些字段已经
        // 不在 schema 里，不会被上面那圈循环摘掉——必须显式删掉，否则会把密钥明文回给前端。
        if (row != null && Integer.valueOf(6).equals(row.getChannelType())) {
            for (String legacy : List.of("inboundMode", "inboundWebhookSecret",
                    "outboundMode", "outboundWebhook", "outboundSecret")) {
                values.remove(legacy);
            }
        }
        return values;
    }

    /** 保存：密钥字段传空表示"不修改"，避免前端回显时把密钥清掉 */
    @Transactional
    public ChannelNativeConfig save(String tenantCode, Long channelId, Map<String, Object> body, String operator) {
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null || Boolean.TRUE.equals(channel.getDeleted())) {
            throw new BizException(40401, "渠道不存在");
        }
        List<NativeField> schema = fields(channel.getChannelType());
        if (schema.isEmpty()) {
            throw new BizException(40001, "这个渠道类型不需要原生接入配置（网站 / 公众号 / 小程序请用对应的配置入口）");
        }
        Map<String, Object> merged = new LinkedHashMap<>(raw(tenantCode, channelId));
        for (NativeField field : schema) {
            if (!body.containsKey(field.name())) {
                continue;
            }
            Object value = body.get(field.name());
            String text = value == null ? "" : String.valueOf(value).trim();
            if (field.secret() && text.isEmpty()) {
                continue;
            }
            if (field.required() && text.isEmpty()) {
                throw new BizException(40001, field.label() + " 不能为空");
            }
            if ("json".equals(field.type()) && !text.isEmpty()) {
                parseJsonList(text, field.label());
            }
            if ("select".equals(field.type()) && !text.isEmpty() && !field.options().contains(text)) {
                throw new BizException(40001, field.label() + "只能是：" + String.join(" / ", field.options()));
            }
            merged.put(field.name(), text);
        }
        if (channel.getChannelType() != null && channel.getChannelType() == 6) {
            validateMailbox(merged);
        }
        String json;
        try {
            json = MAPPER.writeValueAsString(merged);
        } catch (Exception e) {
            throw new BizException(50001, "配置序列化失败：" + e.getMessage());
        }
        ChannelNativeConfig exists = row(tenantCode, channelId);
        LocalDateTime now = LocalDateTime.now();
        if (exists == null) {
            exists = ChannelNativeConfig.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenantCode)
                    .channelId(channelId)
                    .configJson(json)
                    .status(1)
                    .createTime(now)
                    .updateTime(now)
                    .creator(operator)
                    .deleted(false)
                    .build();
            configMapper.insert(exists);
        } else {
            exists.setConfigJson(json);
            exists.setUpdateTime(now);
            exists.setEditor(operator);
            configMapper.updateById(exists);
        }
        log.info("原生渠道配置已保存 tenant={} channelId={} channelType={} 字段={}",
                tenantCode, channelId, channel.getChannelType(), merged.keySet());
        return exists;
    }

    /** 回调地址：端上 / 网关要填的就是它 */
    public Map<String, String> webhookUrls(int channelType, String appKey) {
        String base = callbackBaseUrl == null ? "" : callbackBaseUrl.replaceAll("/+$", "");
        Map<String, String> urls = new LinkedHashMap<>();
        switch (channelType) {
            case 4 -> urls.put("message", base + "/api/customer/native/app/" + appKey + "/message");
            case 5 -> urls.put("event", base + "/api/customer/native/phone/" + appKey + "/event");
            case 6 -> {
                urls.put("inbound", base + "/api/customer/native/mail/" + appKey + "/inbound");
            }
            default -> {
            }
        }
        return urls;
    }

    public String appKeyOf(Long channelId) {
        ChannelKey key = channelKeyMapper.selectOne(Wrappers.<ChannelKey>lambdaQuery()
                .eq(ChannelKey::getChannelId, channelId)
                .eq(ChannelKey::getStatus, 1)
                .eq(ChannelKey::getDeleted, false)
                .orderByDesc(ChannelKey::getId)
                .last("LIMIT 1"));
        if (key == null) {
            throw new BizException(40401, "这个渠道还没有可用密钥，请先在渠道里生成密钥");
        }
        return key.getAppKey();
    }

    /** 按渠道密钥反查渠道（webhook 免登录，靠它认渠道） */
    public Channel channelByAppKey(String appKey) {
        ChannelKey key = channelKeyMapper.selectOne(Wrappers.<ChannelKey>lambdaQuery()
                .eq(ChannelKey::getAppKey, appKey)
                .eq(ChannelKey::getStatus, 1)
                .eq(ChannelKey::getDeleted, false)
                .last("LIMIT 1"));
        if (key == null) {
            throw new BizException(40401, "渠道密钥无效或已停用");
        }
        Channel channel = channelMapper.selectById(key.getChannelId());
        if (channel == null || Boolean.TRUE.equals(channel.getDeleted())) {
            throw new BizException(40401, "渠道不存在");
        }
        if (!Integer.valueOf(1).equals(channel.getStatus())) {
            throw new BizException(40301, "渠道已停用，暂时无法接入");
        }
        return channel;
    }

    private static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 邮件渠道（方案 A）的必填校验：收信要能连上邮箱，所以 IMAP 三项必须有。
     *
     * <p>SMTP 是发信用的（受理回执 / 坐席回复 / 结单回访），不填也能收信建单，
     * 只是外发邮件发不出去，所以不设为必填，在字段说明里提示。</p>
     */
    private static void validateMailbox(Map<String, Object> merged) {
        requireField(merged, "imapHost", "IMAP 服务器");
        requireField(merged, "imapUsername", "IMAP 账号");
        requireField(merged, "imapPassword", "IMAP 密码 / 授权码");
    }

    private static void requireField(Map<String, Object> merged, String name, String label) {
        Object value = merged.get(name);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BizException(40001, label + "不能为空");
        }
    }

    private static List<Map<String, Object>> parseJsonList(String text, String label) {
        try {
            return MAPPER.readValue(text, new TypeReference<ArrayList<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new BizException(40001, label + " 不是合法的 JSON 数组");
        }
    }
}
