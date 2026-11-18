package io.supportflow.customer.service.channel;

import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.customer.dto.channel.ChannelResult;
import io.supportflow.customer.entity.Channel;
import io.supportflow.customer.entity.ChannelKey;
import io.supportflow.customer.entity.ChannelTestLog;
import io.supportflow.customer.entity.SkillGroup;
import io.supportflow.customer.mapper.ChannelKeyMapper;
import io.supportflow.customer.mapper.ChannelMapper;
import io.supportflow.customer.mapper.ChannelTestLogMapper;
import io.supportflow.customer.mapper.SkillGroupMapper;
import io.supportflow.customer.internal.UserNameClient;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 企业渠道接入：创建渠道、生成密钥、联调测试、绑定技能组上线。
 */
@Service
public class ChannelOnboardingService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChannelMapper channelMapper;
    private final ChannelKeyMapper channelKeyMapper;
    private final SkillGroupMapper skillGroupMapper;
    private final ChannelTestLogMapper testLogMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final UserNameClient userNameClient;

    public ChannelOnboardingService(
            ChannelMapper channelMapper,
            ChannelKeyMapper channelKeyMapper,
            SkillGroupMapper skillGroupMapper,
            ChannelTestLogMapper testLogMapper,
            SnowflakeIdGenerator idGenerator,
            UserNameClient userNameClient
    ) {
        this.channelMapper = channelMapper;
        this.channelKeyMapper = channelKeyMapper;
        this.skillGroupMapper = skillGroupMapper;
        this.testLogMapper = testLogMapper;
        this.idGenerator = idGenerator;
        this.userNameClient = userNameClient;
    }

    @Transactional
    public ChannelResult create(LoginUser user, int channelType, String name, String desc) {
        String tenant = tenantOf(user);
        // 1-网站 2-公众号 3-小程序 4-App 5-400热线 6-邮件 7-自定义（见 customer_db.sql 的类型码注释）
        if (channelType < 1 || channelType > 6) {
            throw new BizException(40001, "渠道类型只支持 1-官网 / 2-微信公众号 / 3-微信小程序 / 4-App / 5-400热线 / 6-邮件");
        }
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new BizException(40001, "请填写渠道名称");
        }
        // 同一企业同一种渠道只允许一个「生效中」：再建一个新的会让"这个渠道到底走哪条"变成谜，
        // 会话统计、技能组绑定、密钥都会对不上。要换就先把旧的停用。
        Channel active = activeChannelOfType(tenant, channelType, null);
        if (active != null) {
            throw new BizException(40001, "「" + typeText(channelType) + "」已经有一个生效中的渠道（"
                    + active.getName() + "）。同一企业同一种渠道只能有一个生效中："
                    + "请先停用现有的，或直接使用它。");
        }

        SkillGroup skillGroup = ensureDefaultSkillGroup(user.userId(), tenant);
        long channelPk = idGenerator.nextId();
        String channelId = "CH" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
        Channel channel = Channel.builder()
                .id(channelPk)
                .tenantCode(tenant)
                .channelId(channelId)
                .channelType(channelType)
                .name(name.trim())
                .desc(desc == null || desc.isBlank() ? null : desc.trim())
                .skillGroupId(skillGroup.getId())
                .status(1)
                .stage(3)
                .isEnabled(true)
                // creator 存创建人用户 ID（口径 A），姓名由 user-service 关联展示
                .creator(String.valueOf(user.userId()))
                .deleted(false)
                .build();
        try {
            channelMapper.insert(channel);
        } catch (DuplicateKeyException e) {
            // 并发下两个请求可能同时通过上面那次"先查后写"，最后靠数据库的部分唯一索引兜住；
            // 这里把数据库的原生报错翻成人话，别让用户看到一串索引名。
            throw new BizException(40001, "「" + typeText(channelType)
                    + "」已经有一个生效中的渠道，请先停用旧的再新建");
        }

        String appKey = randomKey();
        channelKeyMapper.insert(ChannelKey.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .channelId(channelPk)
                .appKey(appKey)
                .keyType(1)
                .status(1)
                .creator(String.valueOf(user.userId()))
                .deleted(false)
                .build());
        return toResult(channel, appKey, skillGroup, user.name());
    }

    @Transactional
    public List<ChannelResult> list(LoginUser user) {
        String tenant = tenantOf(user);
        SkillGroup group = defaultGroup(tenant);
        List<Channel> rows = channelMapper.selectList(
                        Wrappers.<Channel>lambdaQuery()
                                .eq(Channel::getTenantCode, tenant)
                                .eq(Channel::getDeleted, false)
                                .orderByDesc(Channel::getCreateTime));
        // 流程已简化，启用渠道统一视为运行中，兼容历史 stage=1/2 数据
        rows.stream()
                .filter(c -> c.getStatus() != null && c.getStatus() == 1 && c.getStage() != null && c.getStage() != 3)
                .forEach(c -> {
                    c.setStage(3);
                    channelMapper.updateById(c);
                });
        Map<String, String> names = userNameClient.namesOf(rows.stream().map(Channel::getCreator).toList());
        // 每个渠道名下挂的是它自己绑的技能组，不能统一塞默认组
        Map<Long, SkillGroup> groups = skillGroupMapper.selectList(Wrappers.<SkillGroup>lambdaQuery()
                        .eq(SkillGroup::getTenantCode, tenant)
                        .eq(SkillGroup::getDeleted, false))
                .stream()
                .collect(Collectors.toMap(SkillGroup::getId, item -> item, (a, b) -> a));
        return rows.stream()
                .map(c -> toResult(c, appKeyOf(c.getId()), groups.get(c.getSkillGroupId()),
                        names.getOrDefault(c.getCreator(), c.getCreator())))
                .toList();
    }

    @Transactional
    public ChannelResult rotateKey(LoginUser user, long channelId) {
        String tenant = tenantOf(user);
        Channel channel = requireOwned(channelId, tenant);
        List<ChannelKey> keys = channelKeyMapper.selectList(
                Wrappers.<ChannelKey>lambdaQuery()
                        .eq(ChannelKey::getChannelId, channel.getId())
                        .eq(ChannelKey::getStatus, 1)
                        .eq(ChannelKey::getDeleted, false));
        for (ChannelKey key : keys) {
            key.setStatus(2);
            key.setRotatedAt(LocalDateTime.now());
            key.setEditor(String.valueOf(user.userId()));
            key.setUpdateTime(LocalDateTime.now());
            channelKeyMapper.updateById(key);
        }
        String appKey = randomKey();
        channelKeyMapper.insert(ChannelKey.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .channelId(channel.getId())
                .appKey(appKey)
                .keyType(1)
                .status(1)
                .creator(String.valueOf(user.userId()))
                .deleted(false)
                .build());
        return toResult(channel, appKey, defaultGroup(tenant), user.name());
    }

    @Transactional
    public ChannelResult updateStatus(LoginUser user, long channelId, boolean enabled) {
        String tenant = tenantOf(user);
        Channel channel = requireOwned(channelId, tenant);
        if (enabled) {
            // 启用是第二道闸门：允许存在多条同类型记录（历史/待替换），但同一时刻只能有一条生效。
            int type = channel.getChannelType() == null ? 0 : channel.getChannelType();
            Channel active = activeChannelOfType(tenant, type, channel.getId());
            if (active != null) {
                throw new BizException(40001, "「" + typeText(type) + "」已有一个生效中的渠道（"
                        + active.getName() + "），请先停用那个再启用这个。");
            }
        }
        channel.setStatus(enabled ? 1 : 2);
        channel.setIsEnabled(enabled);
        channel.setEditor(String.valueOf(user.userId()));
        channel.setUpdateTime(LocalDateTime.now());
        try {
            channelMapper.updateById(channel);
        } catch (DuplicateKeyException e) {
            throw new BizException(40001, "这种渠道已经有一个生效中的了，请先停用它再启用这个");
        }
        return toResult(channel, appKeyOf(channelId), defaultGroup(tenant), user.name());
    }

    private SkillGroup ensureDefaultSkillGroup(long userId, String tenant) {
        SkillGroup group = defaultGroup(tenant);
        if (group != null) {
            return group;
        }
        SkillGroup created = SkillGroup.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .name("默认技能组")
                .description("系统自动创建的默认技能组，可后续在系统设置中调整")
                .isDefault(true)
                .isEnabled(true)
                .creator(String.valueOf(userId))
                .deleted(false)
                .build();
        skillGroupMapper.insert(created);
        return created;
    }

    private SkillGroup defaultGroup(String tenant) {
        return skillGroupMapper.selectOne(
                Wrappers.<SkillGroup>lambdaQuery()
                        .eq(SkillGroup::getTenantCode, tenant)
                        .eq(SkillGroup::getDeleted, false)
                        .orderByAsc(SkillGroup::getId)
                        .last("LIMIT 1"));
    }

    private Channel requireOwned(long channelId, String tenant) {
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null || !tenant.equals(channel.getTenantCode()) || Boolean.TRUE.equals(channel.getDeleted())) {
            throw new BizException(40401, "渠道不存在");
        }
        return channel;
    }

    /**
     * 同一租户同一种渠道里"生效中"的那一条（可排除自己）。
     *
     * <p>口径是 {@code status=1}（生效中）：列表上的「已禁用」不算——所以允许保留历史渠道做替换，
     * 只是不能同时生效两条。</p>
     */
    private Channel activeChannelOfType(String tenant, int channelType, Long excludeId) {
        return channelMapper.selectOne(Wrappers.<Channel>lambdaQuery()
                .eq(Channel::getTenantCode, tenant)
                .eq(Channel::getChannelType, channelType)
                .eq(Channel::getStatus, 1)
                .eq(Channel::getDeleted, false)
                .ne(excludeId != null, Channel::getId, excludeId)
                .orderByAsc(Channel::getId)
                .last("LIMIT 1"));
    }

    /** 渠道类型的人话名字（报错信息里要能一眼看懂是哪种渠道） */
    private static String typeText(int channelType) {
        return switch (channelType) {
            case 1 -> "网站在线客服";
            case 2 -> "微信公众号";
            case 3 -> "微信小程序";
            case 4 -> "官方 App";
            case 5 -> "400 电话热线";
            case 6 -> "邮件工单";
            default -> "渠道 " + channelType;
        };
    }

    private String appKeyOf(Long channelPk) {
        ChannelKey key = channelKeyMapper.selectOne(
                Wrappers.<ChannelKey>lambdaQuery()
                        .eq(ChannelKey::getChannelId, channelPk)
                        .eq(ChannelKey::getStatus, 1)
                        .eq(ChannelKey::getDeleted, false)
                        .last("LIMIT 1"));
        return key == null ? null : key.getAppKey();
    }

    private String tenantOf(LoginUser user) {
        String tenant = user.tenantCode();
        if (tenant == null || tenant.isBlank() || "PLATFORM".equals(tenant)) {
            throw new BizException(40301, "请先完成企业开通后再接入渠道");
        }
        return tenant;
    }

    private String randomKey() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private ChannelResult toResult(Channel channel, String appKey, SkillGroup group, String creatorName) {
        return new ChannelResult(
                String.valueOf(channel.getId()),
                channel.getChannelId(),
                channel.getChannelType(),
                channel.getName(),
                channel.getDesc(),
                channel.getStatus(),
                channel.getStage(),
                appKey,
                group == null ? null : group.getId(),
                creatorName == null ? channel.getCreator() : creatorName,
                channel.getCreateTime()
        );
    }

}
