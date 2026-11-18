package io.supportflow.ops.service;

import io.supportflow.common.api.IdVO;
import io.supportflow.common.audit.AuditLogClient;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.common.util.Texts;
import io.supportflow.ops.dto.AnnouncementBody;
import io.supportflow.ops.dto.AnnouncementPageVO;
import io.supportflow.ops.dto.AnnouncementRowVO;
import io.supportflow.ops.dto.AnnouncementStatusVO;
import io.supportflow.ops.dto.ConfigJson;
import io.supportflow.ops.dto.RegisterConfigBody;
import io.supportflow.ops.dto.RegisterConfigVO;
import io.supportflow.ops.entity.Announcement;
import io.supportflow.ops.entity.RegisterConfig;
import io.supportflow.ops.internal.CustomerNotificationClient;
import io.supportflow.ops.mapper.AnnouncementMapper;
import io.supportflow.ops.mapper.RegisterConfigMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 平台设置：注册审核策略 + 平台公告。
 *
 * <p>套餐价格与支付配置在 billing-service（钱相关的库在那边），这里只管 ops_db 的两块。</p>
 */
@Service
public class PlatformSettingsService {

    private static final Logger log = LoggerFactory.getLogger(PlatformSettingsService.class);

    /** 审计模块名（与平台页筛选器一致） */
    private static final String MODULE = "平台设置";

    /** 公告状态：1-草稿、2-已发布、3-已下线 */
    private static final int ANNOUNCEMENT_DRAFT = 1;
    private static final int ANNOUNCEMENT_PUBLISHED = 2;
    private static final int ANNOUNCEMENT_OFFLINE = 3;

    private final RegisterConfigMapper registerConfigMapper;
    private final AnnouncementMapper announcementMapper;
    private final CustomerNotificationClient notificationClient;
    private final SnowflakeIdGenerator idGenerator;
    /** 审计日志：改注册策略、发公告都是平台级动作，必须留痕 */
    private final AuditLogClient auditLogClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlatformSettingsService(RegisterConfigMapper registerConfigMapper,
                                   AnnouncementMapper announcementMapper,
                                   CustomerNotificationClient notificationClient,
                                   SnowflakeIdGenerator idGenerator,
                                   AuditLogClient auditLogClient) {
        this.registerConfigMapper = registerConfigMapper;
        this.announcementMapper = announcementMapper;
        this.notificationClient = notificationClient;
        this.idGenerator = idGenerator;
        this.auditLogClient = auditLogClient;
    }

    // ------------------------------------------------------------------ 注册与审核策略

    /** 读注册审核策略；没配过就返回一套默认值（不写库，等运营点保存才落） */
    public RegisterConfigVO registerConfig() {
        RegisterConfig config = currentRegisterConfig();
        if (config == null) {
            return new RegisterConfigVO(true, true, 14, List.of(), false, false);
        }
        return toRegisterVO(config);
    }

    /** 保存注册审核策略（全平台一条，upsert） */
    @Transactional
    public RegisterConfigVO saveRegisterConfig(LoginUser operator, RegisterConfigBody body) {
        if (body == null) {
            throw new BizException(40001, "缺少配置内容");
        }
        Integer trialDays = body.trialDays();
        if (trialDays != null && (trialDays < 0 || trialDays > 365)) {
            throw new BizException(40001, "试用时长请填 0~365 天");
        }
        List<String> domains = normalizeDomains(body.autoApproveDomains());
        String configJson = toJson(new ConfigJson(domains, Boolean.TRUE.equals(body.requireDoubleVerify())));

        LocalDateTime now = LocalDateTime.now();
        String operatorName = operator == null ? "系统" : operator.name();
        RegisterConfig current = currentRegisterConfig();
        if (current == null) {
            registerConfigMapper.insert(RegisterConfig.builder()
                    .id(idGenerator.nextId())
                    .allowRegister(!Boolean.FALSE.equals(body.allowRegister()))
                    .needReview(!Boolean.FALSE.equals(body.needReview()))
                    .trialDays((short) (trialDays == null ? 14 : trialDays))
                    .configJson(configJson)
                    .createTime(now)
                    .updateTime(now)
                    .creator(operatorName)
                    .deleted(false)
                    .build());
        } else {
            RegisterConfig update = new RegisterConfig();
            update.setId(current.getId());
            update.setAllowRegister(!Boolean.FALSE.equals(body.allowRegister()));
            update.setNeedReview(!Boolean.FALSE.equals(body.needReview()));
            update.setTrialDays((short) (trialDays == null ? 14 : trialDays));
            update.setConfigJson(configJson);
            update.setUpdateTime(now);
            update.setEditor(operatorName);
            registerConfigMapper.updateById(update);
        }
        auditLogClient.record(operator, MODULE, "保存注册审核策略", "配置", "register_config", true,
                "开放注册=" + (!Boolean.FALSE.equals(body.allowRegister()))
                        + "，需运营审核=" + (!Boolean.FALSE.equals(body.needReview()))
                        + "，试用 " + (trialDays == null ? 14 : trialDays) + " 天"
                        + "，白名单域名 " + domains.size() + " 个"
                        + "，双重验证=" + Boolean.TRUE.equals(body.requireDoubleVerify()));
        return registerConfig();
    }

    // ------------------------------------------------------------------ 平台公告

    /** 公告列表（按状态 / 类型 / 关键字筛选） */
    public AnnouncementPageVO announcements(Integer status, Integer announceType, String keyword,
                                            int page, int pageSize) {
        String kw = Texts.blankToNull(keyword);
        long total = announcementMapper.countAnnouncements(status, announceType, kw);
        List<Map<String, Object>> rows = announcementMapper.selectAnnouncements(status, announceType, kw,
                pageSize, (long) (page - 1) * pageSize);
        List<AnnouncementRowVO> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            list.add(new AnnouncementRowVO(stringOf(row.get("id")), stringOf(row.get("title")),
                    stringOf(row.get("content")), intOrNull(row.get("announceType")),
                    intOrNull(row.get("status")), localDateTimeOrNull(row.get("publishTime")),
                    localDateTimeOrNull(row.get("createTime")), stringOf(row.get("creator")),
                    stringOf(row.get("editor"))));
        }
        return new AnnouncementPageVO(total, page, pageSize, list);
    }

    /** 新建 / 修改公告（保存为草稿；发布走单独接口） */
    @Transactional
    public IdVO saveAnnouncement(LoginUser operator, Long id, AnnouncementBody body) {
        if (body == null || body.title() == null || body.title().isBlank()) {
            throw new BizException(40001, "公告标题不能为空");
        }
        int type = body.announceType() == null ? 1 : body.announceType();
        if (type < 1 || type > 3) {
            throw new BizException(40001, "公告类型只能是 1-系统公告 / 2-产品更新 / 3-维护通知");
        }
        LocalDateTime now = LocalDateTime.now();
        String operatorName = operator == null ? "系统" : operator.name();
        long targetId;
        if (id == null) {
            targetId = idGenerator.nextId();
            announcementMapper.insert(Announcement.builder()
                    .id(targetId)
                    .title(body.title().trim())
                    .content(body.content())
                    .announceType(type)
                    .status(ANNOUNCEMENT_DRAFT)
                    .createTime(now)
                    .updateTime(now)
                    .creator(operatorName)
                    .deleted(false)
                    .build());
            auditLogClient.record(operator, MODULE, "新建公告", "公告", String.valueOf(targetId), true,
                    "新建公告（草稿）「" + body.title().trim() + "」");
        } else {
            Announcement existing = requireAnnouncement(id);
            if (existing.getStatus() != null && existing.getStatus() == ANNOUNCEMENT_PUBLISHED) {
                throw new BizException(40001, "公告已发布，请先下线再修改");
            }
            targetId = id;
            Announcement update = new Announcement();
            update.setId(id);
            update.setTitle(body.title().trim());
            update.setContent(body.content());
            update.setAnnounceType(type);
            update.setUpdateTime(now);
            update.setEditor(operatorName);
            announcementMapper.updateById(update);
            auditLogClient.record(operator, MODULE, "修改公告", "公告", String.valueOf(id), true,
                    "修改公告「" + body.title().trim() + "」");
        }
        return new IdVO(String.valueOf(targetId));
    }

    /**
     * 发布 / 下线公告。
     *
     * <p>发布时顺带广播到全部租户的通知中心（notify_type=5-公告）；
     * 推送失败不影响"已发布"这个事实，返回里把 delivered 带出来给运营看。</p>
     */
    @Transactional
    public AnnouncementStatusVO changeAnnouncementStatus(LoginUser operator, long id, int status) {
        if (status != ANNOUNCEMENT_PUBLISHED && status != ANNOUNCEMENT_OFFLINE) {
            throw new BizException(40001, "只支持发布（2）或下线（3）");
        }
        Announcement announcement = requireAnnouncement(id);
        LocalDateTime now = LocalDateTime.now();
        Announcement update = new Announcement();
        update.setId(id);
        update.setStatus(status);
        update.setPublishTime(status == ANNOUNCEMENT_PUBLISHED ? now : announcement.getPublishTime());
        update.setUpdateTime(now);
        update.setEditor(operator == null ? "系统" : operator.name());
        announcementMapper.updateById(update);

        int delivered = -1;
        if (status == ANNOUNCEMENT_PUBLISHED) {
            delivered = notificationClient.broadcast(announcement.getTitle(), announcement.getContent(), null);
        }
        auditLogClient.record(operator, MODULE,
                status == ANNOUNCEMENT_PUBLISHED ? "发布公告" : "下线公告",
                "公告", String.valueOf(id), true,
                (status == ANNOUNCEMENT_PUBLISHED ? "发布公告「" : "下线公告「") + announcement.getTitle() + "」"
                        + (status == ANNOUNCEMENT_PUBLISHED
                        ? (delivered < 0 ? "，但推送到租户通知中心失败（可重发）"
                        : "，已推送到 " + delivered + " 个租户的通知中心")
                        : ""));
        return new AnnouncementStatusVO(String.valueOf(id), status, delivered);
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    /** JDBC 的 TIMESTAMP 可能是 java.sql.Timestamp / LocalDateTime / 字符串，统一成 LocalDateTime */
    private static LocalDateTime localDateTimeOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }

    /** 删除公告（逻辑删除；已发布的要先下线） */
    @Transactional
    public void removeAnnouncement(LoginUser operator, long id) {
        Announcement announcement = requireAnnouncement(id);
        if (announcement.getStatus() != null && announcement.getStatus() == ANNOUNCEMENT_PUBLISHED) {
            throw new BizException(40001, "公告已发布，请先下线再删除");
        }
        announcementMapper.deleteById(id);
        auditLogClient.record(operator, MODULE, "删除公告", "公告", String.valueOf(id), true,
                "删除公告「" + announcement.getTitle() + "」");
    }

    // ------------------------------------------------------------------ 内部

    private RegisterConfig currentRegisterConfig() {
        return registerConfigMapper.selectOne(Wrappers.<RegisterConfig>lambdaQuery()
                .eq(RegisterConfig::getDeleted, false)
                .orderByAsc(RegisterConfig::getId)
                .last("LIMIT 1"));
    }

    private RegisterConfigVO toRegisterVO(RegisterConfig config) {
        ConfigJson extra = parseConfigJson(config.getConfigJson());
        return new RegisterConfigVO(!Boolean.FALSE.equals(config.getAllowRegister()),
                !Boolean.FALSE.equals(config.getNeedReview()),
                config.getTrialDays() == null ? 14 : config.getTrialDays().intValue(),
                extra.autoApproveDomains() == null ? List.<String>of() : extra.autoApproveDomains(),
                Boolean.TRUE.equals(extra.requireDoubleVerify()), true);
    }

    private Announcement requireAnnouncement(long id) {
        Announcement announcement = announcementMapper.selectById(id);
        if (announcement == null || Boolean.TRUE.equals(announcement.getDeleted())) {
            throw new BizException(40401, "公告不存在：" + id);
        }
        return announcement;
    }

    private String toJson(ConfigJson extra) {
        try {
            return objectMapper.writeValueAsString(extra);
        } catch (Exception e) {
            return "{}";
        }
    }

    private ConfigJson parseConfigJson(String json) {
        if (json == null || json.isBlank()) {
            return new ConfigJson(List.of(), false);
        }
        try {
            ConfigJson parsed = objectMapper.readValue(json, ConfigJson.class);
            return parsed == null ? new ConfigJson(List.of(), false) : parsed;
        } catch (Exception e) {
            log.warn("解析 register_config.config_json 失败，按默认值处理：{}", e.getMessage());
            return new ConfigJson(List.of(), false);
        }
    }

    /** 域名白名单：去空、去重、小写化（域名大小写不敏感） */
    private static List<String> normalizeDomains(List<String> domains) {
        if (domains == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String domain : domains) {
            if (domain == null || domain.isBlank()) {
                continue;
            }
            String text = domain.trim().toLowerCase();
            if (!result.contains(text)) {
                result.add(text);
            }
        }
        return result;
    }

}
