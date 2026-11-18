package io.supportflow.ops.dto;

/**
 * 处理告警事件（确认 / 忽略）的回执。
 */
public record AlertHandleVO(String id, int handleStatus) {
}
