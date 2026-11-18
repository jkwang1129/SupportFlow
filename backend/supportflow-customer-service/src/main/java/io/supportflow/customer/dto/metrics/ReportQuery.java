package io.supportflow.customer.dto.metrics;

import java.time.LocalDate;

/** 报表筛选条件 */
public record ReportQuery(LocalDate from, LocalDate to, Long agentId, String sort) {
}
