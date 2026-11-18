package io.supportflow.customer.dto.session;

import java.util.List;

/** 技能组视图 */
public record GroupVO(
        String id,
        String name,
        String description,
        boolean isDefault,
        int overflowAfterSeconds,
        List<MemberVO> members,
        List<ChannelVO> channels
) {
}
