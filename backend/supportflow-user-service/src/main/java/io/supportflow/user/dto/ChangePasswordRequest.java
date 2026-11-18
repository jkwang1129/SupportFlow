package io.supportflow.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改密码请求体。
 */
public record ChangePasswordRequest(
        @NotBlank(message = "请输入原密码")
        String oldPassword,
        @NotBlank(message = "请输入新密码")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                message = "新密码需不少于 8 位，且同时包含字母和数字")
        @Size(max = 64)
        String newPassword
) {
}
