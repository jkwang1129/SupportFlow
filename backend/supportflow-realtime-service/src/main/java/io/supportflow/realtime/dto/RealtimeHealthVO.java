package io.supportflow.realtime.dto;

/**
 * 实时网关探活 + 版本自证。
 *
 * @param status          固定 UP，与其它服务的探针口径一致
 * @param internalMessage 是否具备 /internal/message（旧版没有，机器人回复会静默丢失）
 * @param connections     当前长连接数
 */
public record RealtimeHealthVO(String status, boolean internalMessage, int connections) {
}
