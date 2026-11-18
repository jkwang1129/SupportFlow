package io.supportflow.customer.dto.metrics;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 实时大屏 */
public record RealtimeVO(int onlineAgents, int busyAgents, int totalAgents, int queuingSessions,
                         int activeSessions, int botSessions, int sessionToday, int messageToday,
                         int customerMessageToday, int csatCountToday, int csatGoodToday,
                         BigDecimal csatScoreToday, int firstResponseSeconds, int botSessionToday,
                         int botRatio, List<HourPointVO> hourly, List<NameValueVO> channels,
                         List<IntentVO> intents, List<NameValueVO> emotions,
                         List<AgentRankVO> topAgents, List<AgentLiveVO> agentLive,
                         List<TrendPointVO> trend, List<ActivityVO> activities,
                         LocalDateTime serverTime) {
}
