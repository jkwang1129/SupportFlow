package io.supportflow.customer.wechat;

import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.customer.dto.wechat.SaveCommand;
import io.supportflow.customer.entity.Channel;
import io.supportflow.customer.entity.WeChatChannelConfig;
import io.supportflow.customer.mapper.ChannelMapper;
import io.supportflow.customer.mapper.WeChatChannelConfigMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 公众号渠道配置的读写：AppID / AppSecret / Token / EncodingAESKey + 模板消息 ID。
 *
 * <p>回调是按 AppID 路由的（微信只告诉我们接收方是谁），所以这里必须有 byAppId 查询；
 * 管理端读写按 channelId，方便渠道列表里直接进配置页。</p>
 */
@Service
public class WeChatConfigService {

    private static final Logger log = LoggerFactory.getLogger(WeChatConfigService.class);

    private final WeChatChannelConfigMapper configMapper;
    private final ChannelMapper channelMapper;
    private final SnowflakeIdGenerator idGenerator;

    /** 对外回调前缀：微信后台「服务器地址(URL)」要填的完整地址由它拼出来 */
    @Value("${supportflow.wechat.callback-base-url:}")
    private String callbackBaseUrl;

    public WeChatConfigService(WeChatChannelConfigMapper configMapper, ChannelMapper channelMapper,
                               SnowflakeIdGenerator idGenerator) {
        this.configMapper = configMapper;
        this.channelMapper = channelMapper;
        this.idGenerator = idGenerator;
    }

    /** 回调按 AppID 反查（微信推送时只知道收件方 AppID） */
    public WeChatChannelConfig byAppId(String appId) {
        if (appId == null || appId.isBlank()) {
            return null;
        }
        return configMapper.selectOne(Wrappers.<WeChatChannelConfig>lambdaQuery()
                .eq(WeChatChannelConfig::getAppId, appId.trim())
                .eq(WeChatChannelConfig::getStatus, 1)
                .eq(WeChatChannelConfig::getDeleted, false)
                .last("LIMIT 1"));
    }

    public WeChatChannelConfig byChannel(String tenantCode, Long channelId) {
        return configMapper.selectOne(Wrappers.<WeChatChannelConfig>lambdaQuery()
                .eq(WeChatChannelConfig::getTenantCode, tenantCode)
                .eq(WeChatChannelConfig::getChannelId, channelId)
                .eq(WeChatChannelConfig::getDeleted, false)
                .last("LIMIT 1"));
    }

    /**
     * 这个租户下所有**小程序**（platform=2）的配置。
     *
     * <p>发订阅消息时要按 AppID 找 openid：客户可能绑在多个小程序上，
     * 而 openid 在不同 AppID 下不通用，所以必须逐个试（列表通常只有一两条）。</p>
     */
    public List<WeChatChannelConfig> miniappConfigs(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return List.of();
        }
        return configMapper.selectList(Wrappers.<WeChatChannelConfig>lambdaQuery()
                .eq(WeChatChannelConfig::getTenantCode, tenantCode)
                .eq(WeChatChannelConfig::getPlatform, 2)
                .eq(WeChatChannelConfig::getStatus, 1)
                .eq(WeChatChannelConfig::getDeleted, false)
                .orderByAsc(WeChatChannelConfig::getId));
    }

    /** 服务器地址(URL)：粘到公众号后台的那个地址 */
    public String callbackUrl(String appId) {
        String path = "/api/customer/wechat/" + appId + "/callback";
        return callbackBaseUrl == null || callbackBaseUrl.isBlank()
                ? path : callbackBaseUrl.replaceAll("/+$", "") + path;
    }

    /** 网页授权回调地址（公众号后台「网页授权域名」里配的域名要与之匹配） */
    public String oauthCallbackUrl(String appId) {
        String path = "/api/customer/wechat/" + appId + "/oauth/callback";
        return callbackBaseUrl == null || callbackBaseUrl.isBlank()
                ? path : callbackBaseUrl.replaceAll("/+$", "") + path;
    }

    /** 保存配置（新增或更新）。密钥类字段留空表示"不修改"，避免前端回显时把密钥清掉 */
    @Transactional
    public WeChatChannelConfig save(String tenantCode, Long channelId, SaveCommand command, String operator) {
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null || Boolean.TRUE.equals(channel.getDeleted())) {
            throw new BizException(40401, "渠道不存在");
        }
        if (!Integer.valueOf(2).equals(channel.getChannelType())) {
            throw new BizException(40001, "只有「微信公众号」渠道才能配置公众号原生接入");
        }
        validate(command);
        WeChatChannelConfig exists = byChannel(tenantCode, channelId);
        LocalDateTime now = LocalDateTime.now();
        if (exists == null) {
            WeChatChannelConfig config = WeChatChannelConfig.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenantCode)
                    .channelId(channelId)
                    .appId(command.appId().trim())
                    .appSecret(command.appSecret().trim())
                    .token(command.token().trim())
                    .aesKey(command.aesKey().trim())
                    .welcomeText(command.welcomeText())
                    .templateLogistics(command.templateLogistics())
                    .templateTicket(command.templateTicket())
                    .templateCoupon(command.templateCoupon())
                    .subscribeTicket(command.subscribeTicket())
                    .subscribeService(command.subscribeService())
                    .platform(command.platform() == null ? 1 : command.platform())
                    .status(1)
                    .createTime(now)
                    .updateTime(now)
                    .creator(operator)
                    .deleted(false)
                    .build();
            configMapper.insert(config);
            log.info("公众号渠道配置已创建 tenant={} channelId={} appId={}",
                    tenantCode, channelId, config.getAppId());
            return config;
        }
        exists.setAppId(command.appId().trim());
        if (notBlank(command.appSecret())) {
            exists.setAppSecret(command.appSecret().trim());
        }
        if (notBlank(command.token())) {
            exists.setToken(command.token().trim());
        }
        if (notBlank(command.aesKey())) {
            exists.setAesKey(command.aesKey().trim());
        }
        exists.setWelcomeText(command.welcomeText());
        exists.setTemplateLogistics(command.templateLogistics());
        exists.setTemplateTicket(command.templateTicket());
        exists.setTemplateCoupon(command.templateCoupon());
        exists.setSubscribeTicket(command.subscribeTicket());
        exists.setSubscribeService(command.subscribeService());
        if (command.platform() != null) {
            exists.setPlatform(command.platform());
        }
        exists.setStatus(1);
        exists.setUpdateTime(now);
        exists.setEditor(operator);
        configMapper.updateById(exists);
        return exists;
    }

    private void validate(SaveCommand command) {
        if (command == null || !notBlank(command.appId())
                || !notBlank(command.appSecret()) || !notBlank(command.token())) {
            throw new BizException(40001, "AppID、AppSecret、Token 三项必填");
        }
        if (!command.appId().trim().startsWith("wx")) {
            throw new BizException(40001, "AppID 一般是 wx 开头的 18 位字符串，请检查是否填成了原始 ID（gh_ 开头）");
        }
        if (notBlank(command.aesKey())) {
            WeChatCrypt.aesKeyOf(command.aesKey().trim());
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    }
