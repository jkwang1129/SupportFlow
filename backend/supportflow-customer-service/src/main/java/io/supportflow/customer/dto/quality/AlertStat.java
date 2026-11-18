package io.supportflow.customer.dto.quality;

/** 告警总览 */
public record AlertStat(long total, long pending, long severe, long today) {
}
