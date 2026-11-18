package io.supportflow.customer.dto.session;

/**
 * 手动触发一次排队调度的结果（排障用）。
 *
 * @param assigned 这一轮实际分出去几通会话
 */
public record RoutingRunVO(int assigned) {
}
