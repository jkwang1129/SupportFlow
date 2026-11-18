package io.supportflow.tenant.dto;

import java.time.LocalDate;

/** 网关用量上报的一条 */
public record UsageReport(String tenantCode, LocalDate statDate, long requestCount,
                          long rejectedCount, long sessionCount, long aiCallCount) {
}
