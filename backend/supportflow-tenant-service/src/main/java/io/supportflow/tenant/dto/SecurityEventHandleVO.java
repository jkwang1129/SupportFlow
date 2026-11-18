package io.supportflow.tenant.dto;

/**
 * 处置安全事件的回执（2-已确认、3-已忽略）。
 */
public record SecurityEventHandleVO(String id, int handleStatus) {
}
