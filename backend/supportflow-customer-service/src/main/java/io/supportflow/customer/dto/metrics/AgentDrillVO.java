package io.supportflow.customer.dto.metrics;

import java.util.List;

/** 下钻：按天趋势 + 会话明细 */
public record AgentDrillVO(String agentId, String agentName, String from, String to,
                           List<DayPointVO> trend, List<DrillSessionVO> sessions) {
}
