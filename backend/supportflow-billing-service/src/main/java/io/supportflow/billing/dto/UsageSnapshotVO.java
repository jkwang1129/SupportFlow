package io.supportflow.billing.dto;

import java.math.BigDecimal;

/**
 * 账期用量快照，存进 {@code billing_statement.usage_snapshot} 用来解释账单金额。
 */
public record UsageSnapshotVO(String cycleKey, Long sessions, Long messages, Long aiCalls,
                              Integer seats, BigDecimal storageGb) {
}
