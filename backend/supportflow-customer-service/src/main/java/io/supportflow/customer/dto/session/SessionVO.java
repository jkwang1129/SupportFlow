package io.supportflow.customer.dto.session;

import java.time.LocalDateTime;

/** 会话列表 / 详情 */
public record SessionVO(
        String sessionNo,
        Integer status,
        Long agentId,
        Long channelId,
        Long customerId,
        String customerName,
        Integer customerLevel,
        /** 客户编号：工作台上点「客户 360」就是拿它去跳页 */
        String customerNo,
        String source,
        String intent,
        String emotion,
        String botTransferReason,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String lastContent,
        Integer lastSenderType,
        LocalDateTime lastTime,
        Integer msgCount
) {
}
