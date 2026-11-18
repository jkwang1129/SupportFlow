package io.supportflow.customer.dto.metrics;

import java.math.BigDecimal;

/** 按天的会话 / 消息数（坐席绩效折线用）。 */
public record DayPointVO(String day, int sessionCount, int messageCount,
                         BigDecimal csatScore, int firstResponseSeconds) {
}
