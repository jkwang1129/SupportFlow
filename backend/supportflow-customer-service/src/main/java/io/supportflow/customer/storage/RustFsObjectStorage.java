package io.supportflow.customer.storage;

import io.supportflow.common.exception.BizException;
import io.supportflow.customer.config.FileStorageProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.http.apache.ApacheHttpClient;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/**
 * RustFS / MinIO / S3 兼容对象存储实现（参考企业智能知识库 RustFsS3Config + RustFileStorage）。
 *
 * <p>平台所有附件只允许写入对象存储，本地磁盘不再作为存储后端：RustFS 不可用时
 * 上传/下载会直接报错，不会退化成写本地文件，避免出现"文件到底存哪儿了"的问题。</p>
 */
@Component
public class RustFsObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(RustFsObjectStorage.class);
    private static final AwsRequestOverrideConfiguration BUCKET_CHECK_TIMEOUT = AwsRequestOverrideConfiguration.builder()
            .apiCallTimeout(Duration.ofSeconds(2)).apiCallAttemptTimeout(Duration.ofSeconds(1)).build();

    private final S3Client s3Client;
    private final String bucket;
    private final String endpoint;

    public RustFsObjectStorage(FileStorageProperties properties) {
        FileStorageProperties.Rustfs cfg = properties.getRustfs();
        String protocol = cfg.isSecure() ? "https" : "http";
        URI endpoint = URI.create(protocol + "://" + cfg.getEndpoint() + ":" + cfg.getPort());
        this.endpoint = endpoint.toString();
        this.bucket = cfg.getBucket();

        Duration connectTimeout = Duration.ofMillis(cfg.getConnectTimeout());
        Duration readTimeout = Duration.ofMillis(cfg.getReadTimeout());
        Duration writeTimeout = Duration.ofMillis(cfg.getWriteTimeout());

        this.s3Client = S3Client.builder()
                .endpointOverride(endpoint)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(cfg.getAccessKey(), cfg.getSecretKey())))
                .region(Region.of("us-east-1"))
                .serviceConfiguration(S3Configuration.builder()
                        .checksumValidationEnabled(false)
                        .chunkedEncodingEnabled(true)
                        // MinIO / RustFS 这类自建对象存储必须走 path-style，否则会拼成 bucket.域名 访问不到
                        .pathStyleAccessEnabled(true)
                        .build())
                .httpClientBuilder(ApacheHttpClient.builder()
                        .maxConnections(cfg.getMaxConnections())
                        .connectionTimeout(connectTimeout)
                        .socketTimeout(readTimeout))
                .overrideConfiguration(builder -> builder
                        .apiCallTimeout(readTimeout.plus(writeTimeout))
                        .apiCallAttemptTimeout(readTimeout))
                .build();
    }

    @PostConstruct
    public void init() {
        try {
            // 先探测桶是否存在，避免每次启动都做一次无谓的建桶请求
            checkBucket();
            log.info("RustFS 初始化完成: endpoint={}, bucket={}", endpoint, bucket);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                createBucket();
            } else {
                log.warn("RustFS 不可用或无权访问，文件上传/下载将失败: endpoint={}, bucket={}, error={}",
                        endpoint, bucket, e.getMessage());
            }
        } catch (Exception e) {
            // 启动阶段不因为对象存储不可用而中断服务，上传/下载时会给出明确报错
            log.warn("RustFS 初始化失败，文件上传/下载将不可用，请检查 supportflow.file.storage.rustfs 配置: endpoint={}, error={}",
                    endpoint, e.getMessage());
        }
    }

    private void createBucket() {
        try {
            s3Client.createBucket(builder -> builder.bucket(bucket));
            log.info("RustFS 存储桶已创建: endpoint={}, bucket={}", endpoint, bucket);
        } catch (SdkException e) {
            log.warn("RustFS 存储桶创建失败，文件上传/下载将不可用: endpoint={}, bucket={}, error={}",
                    endpoint, bucket, e.getMessage());
        }
    }

    boolean bucketAvailable() {
        try {
            checkBucket();
            return true;
        } catch (SdkException unavailable) {
            return false;
        }
    }

    private void checkBucket() {
        s3Client.headBucket(builder -> builder.bucket(bucket).overrideConfiguration(BUCKET_CHECK_TIMEOUT));
    }

    @Override
    public void upload(String key, InputStream input, long size, String mimeType) {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(mimeType)
                            .build(),
                    RequestBody.fromInputStream(input, size));
            log.info("RustFS 上传成功: bucket={}, key={}, size={}", bucket, key, size);
        } catch (SdkException e) {
            throw storageFailure("上传", e, "文件上传到对象存储失败，请稍后重试");
        }
    }

    @Override
    public InputStream download(String key) {
        try {
            ResponseInputStream<GetObjectResponse> response =
                    s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
            return response;
        } catch (NoSuchKeyException e) {
            log.warn("RustFS 对象不存在: bucket={}, key={}", bucket, key);
            throw new BizException(40401, "文件不存在，请重新上传");
        } catch (SdkException e) {
            throw storageFailure("读取", e, "文件读取失败，请稍后重试");
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            log.info("RustFS 删除成功: bucket={}, key={}", bucket, key);
        } catch (SdkException e) {
            throw storageFailure("删除", e, "文件删除失败，请稍后重试");
        }
    }

    private BizException storageFailure(String operation, SdkException failure, String fallback) {
        int status = failure instanceof S3Exception response ? response.statusCode() : 0;
        log.warn("RustFS {}失败: type={}, status={}", operation, failure.getClass().getSimpleName(), status);
        if (failure instanceof SdkClientException || failure instanceof NoSuchBucketException
                || status == 403 || status == 429 || status >= 500) {
            return new StorageUnavailableException();
        }
        return new BizException(50001, fallback);
    }

    @Override
    public boolean exists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (S3Exception e) {
            return false;
        }
    }
}
