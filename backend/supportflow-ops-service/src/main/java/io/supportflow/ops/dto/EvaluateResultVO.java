package io.supportflow.ops.dto;

/**
 * 手动跑一轮告警评估的结果。
 *
 * @param created  这一轮新落了几条告警事件
 * @param notified 其中推送给租户几条
 */
public record EvaluateResultVO(int created, int notified) {
}
