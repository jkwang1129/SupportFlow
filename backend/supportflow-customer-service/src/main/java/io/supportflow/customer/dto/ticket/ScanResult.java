package io.supportflow.customer.dto.ticket;

/** SLA 扫描结果：扫了多少工单、预警多少、已超时多少。 */
public record ScanResult(int scanned, int alerted, int overdue) {
}
