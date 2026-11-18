package io.supportflow.customer.dto.session;

/** 文件内容 */
public record FileContent(String fileName, String mimeType, byte[] payload) {
}
