package io.supportflow.user.dto;

/**
 * 注册策略（注册页据此决定要不要显示"邮箱验证码"那一栏）。
 *
 * <p>只回两个布尔值：阈值之类的安全参数不对外暴露。</p>
 */
public record RegisterPolicyVO(boolean allowRegister, boolean requireDoubleVerify) {
}
