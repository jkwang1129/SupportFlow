package io.supportflow.user.dto;

/**
 * 注册验证码响应（debugCode 只在本地联调态有值）。
 */
public record RegisterCodeResponse(String maskedEmail, int expireSeconds, String debugCode) {
}
