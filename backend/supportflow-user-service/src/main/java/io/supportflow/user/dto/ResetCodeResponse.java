package io.supportflow.user.dto;

/**
 * 发送重置验证码结果。
 */
public record ResetCodeResponse(String maskedEmail, String debugCode, int expireMinutes) {
}
