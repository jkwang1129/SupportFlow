package io.supportflow.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 重置密码请求体。
 */
public record ResetPasswordRequest(
        @NotBlank(message = "请输入手机号 / 邮箱 / 用户编号")
        @Size(max = 128)
        String account,

        @NotBlank(message = "请输入验证码")
        @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
        String code,

        @NotBlank(message = "请输入新密码")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                message = "密码需不少于 8 位，且同时包含字母和数字"
        )
        String newPassword
) {
}
