package io.supportflow.customer.service.bot;

import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.customer.dto.bot.FileContent;
import io.supportflow.customer.dto.bot.UploadResult;
import io.supportflow.customer.entity.FileMeta;
import io.supportflow.customer.mapper.FileMetaMapper;
import io.supportflow.customer.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * 机器人头像：上传到对象存储 + 按文件 ID 免登录读取。
 *
 * <p>为什么不能复用营业执照那套：营业执照的读取接口要登录（只有审核人员看），
 * 而机器人头像是**给访客看的**——访客没有登录态，拿不到 token，所以必须像聊天图片那样
 * 提供公开的头像资源接口；数据库仅投影已发布头像的文件元数据。</p>
 *
 * <p>头像存 {@code file_meta.biz_type=1}（表注释里本来就把 1 定义成"头像"），
 * 和聊天图片（5）、营业执照（2）分开，便于以后做清理与统计。</p>
 */
@Service
public class BotAvatarService {

    private static final Logger log = LoggerFactory.getLogger(BotAvatarService.class);

    /** 对象键前缀：bot/avatar/{tenant}/{fileId}_{name} */
    private static final String PREFIX = "bot/avatar";
    /** file_meta.biz_type：1-头像（与建表注释一致） */
    private static final int BIZ_TYPE_AVATAR = 1;
    /** 头像不需要大图：2MB 足够放 512×512 的 PNG */
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXT = Set.of("png", "jpg", "jpeg", "webp");

    private final ObjectStorage objectStorage;
    private final FileMetaMapper fileMetaMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final JdbcTemplate jdbc;

    public BotAvatarService(ObjectStorage objectStorage, FileMetaMapper fileMetaMapper,
                            SnowflakeIdGenerator idGenerator, JdbcTemplate jdbc) {
        this.objectStorage = objectStorage;
        this.fileMetaMapper = fileMetaMapper;
        this.idGenerator = idGenerator;
        this.jdbc = jdbc;
    }

    /** 上传头像，返回可直接填进 avatar_url 的访问地址 */
    @Transactional
    public UploadResult upload(LoginUser user, MultipartFile file) {
        if (user == null || user.userType() != 2 || user.tenantCode() == null || user.tenantCode().isBlank()
                || "PLATFORM".equalsIgnoreCase(user.tenantCode())) {
            throw new BizException(40301, "仅企业成员可设置机器人头像");
        }
        if (file == null || file.isEmpty()) {
            throw new BizException(40001, "请选择要上传的头像图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException(40001, "头像不能超过 2MB，建议压到 512×512 以内");
        }
        String original = file.getOriginalFilename() == null ? "avatar.png" : file.getOriginalFilename();
        String ext = extension(original);
        if (ext == null || !ALLOWED_EXT.contains(ext)) {
            throw new BizException(40001, "头像只支持 PNG / JPG / WEBP 图片");
        }
        String tenant = user.tenantCode();

        long fileId = idGenerator.nextId();
        String safeName = original.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        String objectKey = PREFIX + "/" + tenant + "/" + fileId + "_" + safeName;
        String mimeType = mimeOf(ext);
        try (InputStream in = file.getInputStream()) {
            objectStorage.upload(objectKey, in, file.getSize(), mimeType);
        } catch (Exception e) {
            log.error("头像上传对象存储失败 tenant={} error={}", tenant, e.getMessage());
            throw new BizException(50001, "头像上传失败，请稍后重试");
        }

        LocalDateTime now = LocalDateTime.now();
        fileMetaMapper.insert(FileMeta.builder()
                .id(fileId)
                .tenantCode(tenant)
                .fileNo("AVT" + fileId)
                .fileName(original)
                .objectKey(objectKey)
                .fileSize(file.getSize())
                .mimeType(mimeType)
                .bizType(BIZ_TYPE_AVATAR)
                .createTime(now)
                .updateTime(now)
                .creator(user == null ? "SYSTEM" : String.valueOf(user.userId()))
                .deleted(false)
                .build());
        log.info("机器人头像已上传 tenant={} fileId={} {}KB", tenant, fileId, file.getSize() / 1024);
        return new UploadResult(String.valueOf(fileId), accessUrl(fileId), original,
                file.getSize(), mimeType);
    }

    /**
     * 读取头像字节（访客端直接当 img src 用）。
     *
     * <p>头像属于公开品牌资源；受控函数仅返回头像目录下的有效头像，不读取聊天图片或企业附件。</p>
     */
    public FileContent load(long fileId) {
        FileMeta meta = jdbc.query("SELECT * FROM public.supportflow_public_bot_avatar(?)",
                (rs, index) -> FileMeta.builder().fileName(rs.getString("file_name"))
                        .mimeType(rs.getString("mime_type")).objectKey(rs.getString("object_key")).build(),
                fileId).stream().findFirst().orElse(null);
        if (meta == null) {
            throw new BizException(40401, "头像不存在或已被替换");
        }
        try (InputStream in = objectStorage.download(meta.getObjectKey())) {
            return new FileContent(meta.getFileName(),
                    meta.getMimeType() == null ? "image/png" : meta.getMimeType(),
                    in.readAllBytes());
        } catch (Exception e) {
            throw new BizException(50001, "读取头像失败：" + e.getMessage());
        }
    }

    private String accessUrl(long fileId) {
        return "/api/customer/bot/avatar/" + fileId;
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? null : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String mimeOf(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }
}
