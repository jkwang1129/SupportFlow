package io.supportflow.customer.dto.bot;

/** 机器人头像上传结果。 */
public record UploadResult(String fileId, String url, String fileName, long fileSize, String mimeType) {
}
