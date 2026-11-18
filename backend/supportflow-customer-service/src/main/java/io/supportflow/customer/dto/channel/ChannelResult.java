package io.supportflow.customer.dto.channel;

import java.time.LocalDateTime;

/** 渠道接入的一条记录（列表 / 新建 / 改状态都回它）。 */
public record ChannelResult(
        String id,
        String channelId,
        int channelType,
        String name,
        String desc,
        int status,
        int stage,
        String appKey,
        Long skillGroupId,
        String creatorName,
        LocalDateTime createTime
) {
}
