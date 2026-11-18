package io.supportflow.customer.dto.channel;

/** 合成结果：available=false 时 audio 为空、hint 说明原因 */
public record TtsResult(boolean available, byte[] audio, String contentType, String hint) {
}
