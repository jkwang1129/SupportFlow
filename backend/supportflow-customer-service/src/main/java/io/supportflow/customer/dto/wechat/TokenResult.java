package io.supportflow.customer.dto.wechat;

/** access_token 结果 */
public record TokenResult(String token, int expiresIn) {
}
