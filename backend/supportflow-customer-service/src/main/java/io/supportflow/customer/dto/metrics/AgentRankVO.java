package io.supportflow.customer.dto.metrics;

import java.math.BigDecimal;

/** 坐席排行的一项（大屏右侧）。 */
public record AgentRankVO(String agentId, String agentName, int sessionCount,
                          int messageCount, int avgResponseSeconds, BigDecimal csatScore) {
}
