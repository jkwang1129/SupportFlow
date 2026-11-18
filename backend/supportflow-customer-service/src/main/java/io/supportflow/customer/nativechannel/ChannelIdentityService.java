package io.supportflow.customer.nativechannel;

import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.customer.entity.ChannelIdentity;
import io.supportflow.customer.mapper.ChannelIdentityMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 外部身份 ↔ 客户档案的绑定（App 用户 ID / 来电号码 / 发件邮箱）。
 *
 * <p>出站推送也靠它：会话 → 客户 → 该渠道下的外部身份，才知道"这句话要回给谁"。</p>
 */
@Service
public class ChannelIdentityService {

    public static final String TYPE_APP_USER = "app_user";
    public static final String TYPE_PHONE = "phone";
    public static final String TYPE_MAIL = "mail_addr";

    private final ChannelIdentityMapper identityMapper;
    private final SnowflakeIdGenerator idGenerator;

    public ChannelIdentityService(ChannelIdentityMapper identityMapper, SnowflakeIdGenerator idGenerator) {
        this.identityMapper = identityMapper;
        this.idGenerator = idGenerator;
    }

    public ChannelIdentity find(Long channelId, String identityType, String externalId) {
        return identityMapper.selectOne(Wrappers.<ChannelIdentity>lambdaQuery()
                .eq(ChannelIdentity::getChannelId, channelId)
                .eq(ChannelIdentity::getIdentityType, identityType)
                .eq(ChannelIdentity::getExternalId, externalId)
                .eq(ChannelIdentity::getDeleted, false)
                .last("LIMIT 1"));
    }

    /** 会话对应的外部身份（出站推送用：优先同一渠道的绑定） */
    public ChannelIdentity bySessionCustomer(String tenantCode, Long channelId, Long customerId) {
        if (customerId == null) {
            return null;
        }
        return identityMapper.selectOne(Wrappers.<ChannelIdentity>lambdaQuery()
                .eq(ChannelIdentity::getTenantCode, tenantCode)
                .eq(ChannelIdentity::getChannelId, channelId)
                .eq(ChannelIdentity::getCustomerId, customerId)
                .eq(ChannelIdentity::getDeleted, false)
                .orderByDesc(ChannelIdentity::getId)
                .last("LIMIT 1"));
    }

    /** 绑定（没有就建，有就更新会话号）。并发重复绑定时靠唯一索引兜底。 */
    @Transactional
    public ChannelIdentity bind(String tenantCode, Long channelId, String identityType, String externalId,
                                Long customerId, String customerNo, String customerName, String sessionNo) {
        LocalDateTime now = LocalDateTime.now();
        ChannelIdentity exists = find(channelId, identityType, externalId);
        if (exists == null) {
            ChannelIdentity created = ChannelIdentity.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenantCode)
                    .channelId(channelId)
                    .identityType(identityType)
                    .externalId(externalId)
                    .customerId(customerId)
                    .customerNo(customerNo)
                    .customerName(customerName)
                    .sessionNo(sessionNo)
                    .status(1)
                    .bindTime(now)
                    .createTime(now)
                    .updateTime(now)
                    .deleted(false)
                    .build();
            identityMapper.insert(created);
            return created;
        }
        exists.setCustomerId(customerId);
        exists.setCustomerNo(customerNo);
        exists.setCustomerName(customerName);
        exists.setSessionNo(sessionNo);
        exists.setUpdateTime(now);
        exists.setStatus(1);
        identityMapper.updateById(exists);
        return exists;
    }
}
