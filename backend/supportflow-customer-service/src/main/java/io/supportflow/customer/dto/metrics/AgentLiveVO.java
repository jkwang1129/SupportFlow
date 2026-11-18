package io.supportflow.customer.dto.metrics;

import java.math.BigDecimal;

/** 坐席实时状态（大屏「坐席实时状态」卡片） */
public record AgentLiveVO(String agentId, String agentName, int status, String statusText,
                          boolean connected, int activeCount, int sessionCount,
                          BigDecimal csatScore) {
}
