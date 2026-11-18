package io.supportflow.customer.dto.quality;

import java.util.List;

/**
 * 全量扫描结果。
 */
public record ScanResult(int scanned, int created, List<String> taskNos) {
}
