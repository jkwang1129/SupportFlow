package io.supportflow.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发送重置验证码请求体。
 */
public record SendResetCodeRequest(
        @NotBlank(message = "请输入手机号 / 邮箱 / 用户编号")
        @Size(max = 128)
        String account
) {
}
