package io.supportflow.customer.dto.license;

/** 授权文件上传结果（落 file_meta 后的文件 ID 与大小）。 */
public record UploadResult(String fileId, String fileName, long fileSize, String mimeType) {
}
