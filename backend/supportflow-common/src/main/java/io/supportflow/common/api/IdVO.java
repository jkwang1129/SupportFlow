package io.supportflow.common.api;

/**
 * 「刚创建 / 刚改动的那条的 ID」这类写操作的统一回执。
 *
 * <p>ID 用字符串：雪花 ID 有 18 位，前端 JS 的 Number 装不下。</p>
 */
public record IdVO(String id) {
}
