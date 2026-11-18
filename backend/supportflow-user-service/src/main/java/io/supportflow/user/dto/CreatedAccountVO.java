package io.supportflow.user.dto;

/**
 * 新建账号后的回执。
 *
 * <p>{@code initialPassword} 只在这里回显一次：运营转告本人后应当立即要求修改。</p>
 */
public record CreatedAccountVO(long userId, String userNo, String initialPassword) {
}
