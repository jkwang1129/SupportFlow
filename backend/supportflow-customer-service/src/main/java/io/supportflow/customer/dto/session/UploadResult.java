package io.supportflow.customer.dto.session;

/** 上传结果 */
public record UploadResult(String fileId, String url, String name, long size, String mimeType) {
}
