package io.supportflow.customer.storage;

import java.io.InputStream;

/**
 * 对象存储抽象：平台附件统一写入 RustFS(S3 兼容)，业务层不感知存储细节。
 */
public interface ObjectStorage {

    /**
     * 上传对象。
     *
     * @param key      对象键（如 enterprise/license/{tenant}/{fileId}_{name}）
     * @param input    文件流
     * @param size     文件大小
     * @param mimeType 文件 MIME
     */
    void upload(String key, InputStream input, long size, String mimeType);

    /**
     * 读取对象内容。
     */
    InputStream download(String key);

    /**
     * 删除对象。
     */
    void delete(String key);

    /**
     * 对象是否存在。
     */
    boolean exists(String key);
}
