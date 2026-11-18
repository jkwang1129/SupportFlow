package io.supportflow.customer.dto.bot;

/** 机器人头像文件的字节内容。 */
public record FileContent(String fileName, String mimeType, byte[] bytes) {
}
