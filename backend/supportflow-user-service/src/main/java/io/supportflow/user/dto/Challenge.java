package io.supportflow.user.dto;

/** 开挑战的结果：挑战号 + 明文验证码（只在这里出现一次，之后只在邮件里） */
public record Challenge(String challengeId, String code) {
}
