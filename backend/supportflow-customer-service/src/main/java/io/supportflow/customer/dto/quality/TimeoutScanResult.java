package io.supportflow.customer.dto.quality;

/**
 * 响应超时扫描结果。
 *
 * @param scanned 本轮扫到多少条"客户在等"的会话
 * @param alerted 实际新增多少条提醒（重复的、没到点的都不算）
 */
public record TimeoutScanResult(int scanned, int alerted) {
}
