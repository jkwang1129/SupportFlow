package io.supportflow.customer.dto.license;

import java.io.InputStream;

/** 下载授权文件的内容（营业执照等）。 */
public record FileContent(String fileName, String mimeType, InputStream inputStream) {
}
