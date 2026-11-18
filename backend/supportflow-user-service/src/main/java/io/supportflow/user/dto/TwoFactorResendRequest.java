package io.supportflow.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录第二步：重发验证码（挑战号不变，前端不用退回第一步）。
 */
public record TwoFactorResendRequest(
        @NotBlank(message = "缺少挑战号，请重新登录")
        @Size(max = 64, message = "挑战号不合法")
        String challengeId
) {
}
