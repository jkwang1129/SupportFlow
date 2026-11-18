package io.supportflow.user.dto;

/** 注册验证码的发送结果（debugCode 只在联调态有值） */
public record RegisterCodeResult(String maskedEmail, int expireSeconds, String debugCode) {
}
