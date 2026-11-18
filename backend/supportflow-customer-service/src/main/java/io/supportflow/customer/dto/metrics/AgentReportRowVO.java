package io.supportflow.customer.dto.metrics;

import java.math.BigDecimal;

/** 报表一行 */
public record AgentReportRowVO(String agentId, String agentName, int activeDays, int sessionCount,
                               int humanSessionCount, int messageCount, int customerMessageCount,
                               int firstResponseSeconds, int avgResponseSeconds, int avgSessionSeconds,
                               int transferCount, int csatCount, BigDecimal csatScore,
                               int goodCsatCount, int closeCount, BigDecimal sessionPerDay) {
}
