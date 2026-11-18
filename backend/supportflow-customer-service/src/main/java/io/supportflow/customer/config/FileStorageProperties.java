package io.supportflow.customer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对象存储配置（RustFS / MinIO / S3 兼容）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "supportflow.file.storage")
public class FileStorageProperties {

    /** 单文件大小上限（字节） */
    private long maxSize = 10L * 1024 * 1024;

    /** RustFS(S3) 配置 */
    private Rustfs rustfs = new Rustfs();

    @Data
    public static class Rustfs {
        /** 服务端点（与企业知识库项目共用同一套 RustFS） */
        private String endpoint = "127.0.0.1";
        /** 服务端口 */
        private int port = 9000;
        /** 访问密钥 ID */
        private String accessKey = "SUPPORTFLOWLOCAL";
        /** 秘密访问密钥 */
        private String secretKey = "supportflow-local-storage";
        /** 存储桶 */
        private String bucket = "supportflow";
        /** 是否使用 HTTPS */
        private boolean secure = false;
        /** 连接超时（毫秒） */
        private int connectTimeout = 30000;
        /** 读取超时（毫秒） */
        private int readTimeout = 60000;
        /** 写入超时（毫秒） */
        private int writeTimeout = 60000;
        /** 连接池最大连接数 */
        private int maxConnections = 50;
    }
}
