package io.supportflow.customer.service.license;

import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.customer.config.FileStorageProperties;
import io.supportflow.customer.dto.license.FileContent;
import io.supportflow.customer.dto.license.UploadResult;
import io.supportflow.customer.entity.FileMeta;
import io.supportflow.customer.mapper.FileMetaMapper;
import io.supportflow.customer.storage.ObjectStorage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 企业营业执照附件：上传到对象存储并记录 file_meta，供企业资料审核使用。
 */
@Service
public class EnterpriseLicenseFileService {

    private static final String LICENSE_PREFIX = "enterprise/license";

    private final ObjectStorage objectStorage;
    private final FileMetaMapper fileMetaMapper;
    private final FileStorageProperties properties;
    private final SnowflakeIdGenerator idGenerator;

    public EnterpriseLicenseFileService(
            ObjectStorage objectStorage,
            FileMetaMapper fileMetaMapper,
            FileStorageProperties properties,
            SnowflakeIdGenerator idGenerator
    ) {
        this.objectStorage = objectStorage;
        this.fileMetaMapper = fileMetaMapper;
        this.properties = properties;
        this.idGenerator = idGenerator;
    }

    @Transactional
    public UploadResult uploadLicense(LoginUser user, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(40001, "请选择营业执照文件");
        }
        if (file.getSize() > properties.getMaxSize()) {
            throw new BizException(40001, "营业执照文件不能超过 10MB");
        }
        String original = file.getOriginalFilename() == null ? "license" : file.getOriginalFilename();
        String ext = extension(original);
        if (ext == null || !ext.matches("(?i)png|jpe?g|pdf")) {
            throw new BizException(40001, "仅支持 JPG / PNG / PDF 格式的营业执照");
        }

        long fileId = idGenerator.nextId();
        String tenant = user.tenantCode() == null || user.tenantCode().isBlank()
                ? "PLATFORM"
                : user.tenantCode();
        String safeName = original.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        String objectKey = LICENSE_PREFIX + "/" + tenant + "/" + fileId + "_" + safeName;

        try (InputStream in = file.getInputStream()) {
            objectStorage.upload(objectKey, in, file.getSize(), file.getContentType());
        } catch (IOException e) {
            throw new BizException(50001, "营业执照上传失败，请稍后重试");
        }

        String fileNo = "F" + fileId;
        fileMetaMapper.insert(FileMeta.builder()
                .id(fileId)
                .tenantCode(tenant)
                .fileNo(fileNo)
                .fileName(original)
                .objectKey(objectKey)
                .fileSize(file.getSize())
                .mimeType(file.getContentType())
                .bizType(2)
                .creator(String.valueOf(user.userId()))
                .deleted(false)
                .build());
        return new UploadResult(String.valueOf(fileId), original, file.getSize(), file.getContentType());
    }

    @Transactional(readOnly = true)
    public FileContent loadLicense(LoginUser user, long fileId) {
        FileMeta meta = fileMetaMapper.selectOne(
                Wrappers.<FileMeta>lambdaQuery()
                        .eq(FileMeta::getId, fileId)
                        .eq(FileMeta::getBizType, 2)
                        .eq(FileMeta::getDeleted, false)
                        .last("LIMIT 1")
        );
        if (meta == null) {
            throw new BizException(40401, "营业执照文件不存在");
        }
        return new FileContent(meta.getFileName(), meta.getMimeType(), objectStorage.download(meta.getObjectKey()));
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? null : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
