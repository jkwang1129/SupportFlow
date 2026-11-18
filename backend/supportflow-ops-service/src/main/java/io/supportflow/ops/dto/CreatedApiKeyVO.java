package io.supportflow.ops.dto;

/**
 * 新建密钥的回执。
 *
 * <p>{@code apiKey} 是明文，**只在这一个响应里回显一次**，库里存的是哈希。</p>
 */
public record CreatedApiKeyVO(String id, String apiKey) {
}
