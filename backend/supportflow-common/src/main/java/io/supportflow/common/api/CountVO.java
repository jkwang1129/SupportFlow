package io.supportflow.common.api;

/**
 * 「一个计数」的接口回执（未读数、待办数这类）。
 *
 * <p>单独一个记录而不是直接回数字：以后要加"上一页游标"之类，
 * 加字段不会破坏调用方；前端也不必猜这个数字是什么口径。</p>
 */
public record CountVO(int count) {
}
