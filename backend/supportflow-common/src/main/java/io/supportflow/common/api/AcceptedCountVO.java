package io.supportflow.common.api;

/**
 * 「收到 N 条」的写入类内部接口回执（网关/其它服务批量上报时用）。
 */
public record AcceptedCountVO(int accepted) {
}
