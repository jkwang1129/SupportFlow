package io.supportflow.billing.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 计量上报入参 */
public record UsageReport(String tenantCode, LocalDate statDate, long sessions, long messages,
                          long aiCalls, int seats, BigDecimal storageGb) {
}
