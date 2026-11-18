package io.supportflow.common.api;

/**
 * 各服务统一的 /ping 探针返回。
 *
 * <p>只有两个用途：确认服务起来了、确认租户上下文能透传。故意做成固定字段而不是
 * 一个 Map——网关与巡检脚本都按这两个字段取值，写死之后改名会在这里编译不过，
 * 而不是等到线上才发现取不到。</p>
 *
 * @param service    服务名（如 customer-service）
 * @param tenantCode 本次请求带上来的租户编码，没带就是空串
 */
public record ServicePing(String service, String tenantCode) {
}
