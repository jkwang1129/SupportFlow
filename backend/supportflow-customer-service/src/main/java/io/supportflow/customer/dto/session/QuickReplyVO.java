package io.supportflow.customer.dto.session;

/**
 * 快捷话术的一行。
 *
 * @param altIndex 全局快捷键序号；第 10 条起没有快捷键，这里是 null（保持原样显式给 null）
 */
public record QuickReplyVO(String id, String title, String content, String category,
                           Integer sortNo, boolean isEnabled, Long useCount,
                           Integer altIndex, String shortcut, String updateTime) {
}
