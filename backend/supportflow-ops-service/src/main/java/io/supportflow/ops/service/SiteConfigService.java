package io.supportflow.ops.service;

import io.supportflow.common.audit.AuditLogClient;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.ops.dto.SiteConfigEditorVO;
import io.supportflow.ops.entity.SiteConfig;
import io.supportflow.ops.mapper.SiteConfigMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 官网配置：可视化编辑 → 预览 → 发布。
 *
 * <p>数据落 {@code ops_db.site_config}（基线脚本里就有这张表，之前没人用）。一个区块一行，
 * 用版本号 + 状态区分草稿与已发布：</p>
 * <ol>
 *   <li><b>草稿</b>永远坐在"已发布最大版本 + 1"这个版本号上，改多少次都只更新这一版；</li>
 *   <li><b>发布</b>把这一版草稿行原地改成"已发布"并记发布时间——版本号只增不减，历史留着；</li>
 *   <li><b>公开接口</b>只读已发布的最高版本，缺哪个区块就用 {@code resources/site-config.json}
 *       的默认值补哪个区块，所以运营改一半也不会把官网搞白屏。</li>
 * </ol>
 *
 * <p>为什么默认值放代码仓库而不是数据库初始化脚本：它是"官网的出厂文案"，要能 diff、能评审；
 * 库里存的只是"运营改过的那部分"。</p>
 */
@Service
public class SiteConfigService {

    private static final Logger log = LoggerFactory.getLogger(SiteConfigService.class);

    /** 审计模块名（与平台页筛选器一致） */
    private static final String MODULE = "官网配置";

    /** 状态码：1-草稿、2-已发布（与 ck_site_config_status 对齐） */
    private static final int STATUS_DRAFT = 1;
    private static final int STATUS_PUBLISHED = 2;

    /** 默认配置：出厂文案，缺哪个区块就用它兜底 */
    private static final String DEFAULT_RESOURCE = "site-config.json";

    /**
     * 允许编辑的区块（与官网页面的 data-cfg 契约一一对应）。
     *
     * <p>顺序就是编辑器左栏的展示顺序：先首屏，再信任、功能、展示、方案、案例，
     * 最后定价、常见问题、底部 CTA 与页脚。</p>
     */
    public static final List<String> SECTION_KEYS = List.of(
            "hero", "trust", "features", "showcase", "demo",
            "solutions", "cases", "statband", "pricing", "faq", "cta", "footer");

    /** 单个区块的 JSON 上限：防手滑贴进来一整篇文档把库写爆 */
    private static final int MAX_SECTION_CHARS = 20_000;

    /** 单条文案上限 */
    private static final int MAX_TEXT_CHARS = 2_000;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SiteConfigMapper siteConfigMapper;
    private final SnowflakeIdGenerator idGenerator;
    /** 官网配置是平台级动作，改了必须留痕 */
    private final AuditLogClient auditLogClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 出厂文案：进程内缓存一份（它是仓库里的静态文件，改了要重启，没必要每次读盘） */
    private volatile Map<String, Object> defaultCache;

    public SiteConfigService(SiteConfigMapper siteConfigMapper,
                             SnowflakeIdGenerator idGenerator,
                             AuditLogClient auditLogClient) {
        this.siteConfigMapper = siteConfigMapper;
        this.idGenerator = idGenerator;
        this.auditLogClient = auditLogClient;
    }

    // ------------------------------------------------------------------ 读

    /** 编辑器要的全部状态：草稿、已发布、有没有未发布的改动、版本与时间 */
    public SiteConfigEditorVO current() {
        Map<String, Object> published = publishedConfig();
        Map<String, Object> draft = draftConfig(published);
        return new SiteConfigEditorVO(draft, published, !sameSections(draft, published),
                publishedVersion(), draftVersion(), publishedTime(), draftUpdateTime(),
                draftEditor(), defaults());
    }

    /**
     * 官网公开接口读的配置：已发布内容覆盖在出厂默认之上。
     *
     * <p>库还连不上时直接给默认值，这个方法不会往外抛（官网不能白屏）。</p>
     */
    public Map<String, Object> publishedOrDefaults() {
        try {
            return publishedConfig();
        } catch (Exception e) {
            log.warn("读已发布官网配置失败，先用出厂默认：{}", e.getMessage());
            return defaults();
        }
    }

    /** 预览参数：草稿的 base64url(UTF-8 JSON)，官网用 ?site= 直接吃（与 site.js 解码方式一致） */
    public String previewParam() {
        Map<String, Object> published = publishedConfig();
        String json = toJson(draftConfig(published));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 写

    /**
     * 保存草稿（不动线上）。
     *
     * <p>只接受 {@link #SECTION_KEYS} 里的区块；未知区块直接拒绝——写进来也没人读，
     * 静默吞掉只会让运营以为"保存成功了"。</p>
     */
    @Transactional
    public SiteConfigEditorVO saveDraft(LoginUser operator, Map<String, Object> body) {
        Map<String, Object> sections = validate(body);
        int version = draftVersion();
        String operatorName = operator == null ? "系统" : operator.name();
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, Object> entry : sections.entrySet()) {
            upsertDraft(entry.getKey(), version, entry.getValue(), operatorName, now);
        }
        auditLogClient.record(operator, MODULE, "保存官网配置草稿", "官网配置",
                String.valueOf(version), true,
                "保存草稿 v" + version + "，共 " + sections.size() + " 个区块："
                        + String.join("、", sections.keySet()));
        return current();
    }

    /** 恢复默认：把草稿重置为出厂文案（线上版本不受影响，要生效仍需点发布） */
    @Transactional
    public SiteConfigEditorVO resetDraft(LoginUser operator) {
        int version = draftVersion();
        String operatorName = operator == null ? "系统" : operator.name();
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> defaults = defaults();
        for (String key : SECTION_KEYS) {
            upsertDraft(key, version, defaults.get(key), operatorName, now);
        }
        auditLogClient.record(operator, MODULE, "恢复官网配置默认", "官网配置",
                String.valueOf(version), true, "草稿恢复出厂文案（v" + version + "），未发布");
        return current();
    }

    /**
     * 发布：把当前草稿行原地改成"已发布"。
     *
     * <p>原地改而不是插新行，是为了不撞 {@code uk_section_version}；版本号只增不减，
     * 上一个版本的发布行仍然留在表里。</p>
     */
    @Transactional
    public SiteConfigEditorVO publish(LoginUser operator) {
        int version = draftVersion();
        List<SiteConfig> drafts = draftRows(version);
        if (drafts.isEmpty()) {
            throw new BizException(40001, "草稿与已发布一致，没有需要发布的内容");
        }
        LocalDateTime now = LocalDateTime.now();
        String operatorName = operator == null ? "系统" : operator.name();
        SiteConfig update = new SiteConfig();
        update.setStatus(STATUS_PUBLISHED);
        update.setPublishedAt(now);
        update.setUpdateTime(now);
        update.setEditor(operatorName);
        siteConfigMapper.update(update, Wrappers.<SiteConfig>lambdaQuery()
                .eq(SiteConfig::getVersionNo, version)
                .eq(SiteConfig::getStatus, STATUS_DRAFT)
                .eq(SiteConfig::getDeleted, false));
        auditLogClient.record(operator, MODULE, "发布官网配置", "官网配置",
                String.valueOf(version), true,
                "发布官网配置 v" + version + "，共 " + drafts.size() + " 个区块生效");
        return current();
    }

    // ------------------------------------------------------------------ 内部

    /** 出厂默认配置（读不到就返回空对象，官网仍有静态兜底） */
    public Map<String, Object> defaults() {
        Map<String, Object> cached = defaultCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (defaultCache != null) {
                return defaultCache;
            }
            Map<String, Object> loaded = loadDefaults();
            defaultCache = loaded;
            return loaded;
        }
    }

    private Map<String, Object> loadDefaults() {
        try (InputStream in = new ClassPathResource(DEFAULT_RESOURCE).getInputStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> parsed = objectMapper.readValue(json,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception e) {
            log.warn("读 {} 失败，官网配置按空默认处理：{}", DEFAULT_RESOURCE, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** 已发布配置：每个区块取"已发布里最高的版本"，缺的区块用出厂默认补 */
    private Map<String, Object> publishedConfig() {
        Map<String, Object> result = new LinkedHashMap<>(defaults());
        for (Map.Entry<String, Object> entry : pickLatest(rows(STATUS_PUBLISHED)).entrySet()) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /** 草稿配置：没改过就是已发布那份（编辑器打开即所见即所得），改过的区块覆盖上去 */
    private Map<String, Object> draftConfig(Map<String, Object> published) {
        Map<String, Object> result = new LinkedHashMap<>(published);
        for (Map.Entry<String, Object> entry : pickLatest(rows(STATUS_DRAFT)).entrySet()) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /** 每个区块取版本号最大的那一行（同一区块的历史行都留着，取新的） */
    private Map<String, Object> pickLatest(List<SiteConfig> rows) {
        Map<String, SiteConfig> latest = new LinkedHashMap<>();
        for (SiteConfig row : rows) {
            SiteConfig current = latest.get(row.getSectionKey());
            if (current == null || versionOf(row) >= versionOf(current)) {
                latest.put(row.getSectionKey(), row);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, SiteConfig> entry : latest.entrySet()) {
            result.put(entry.getKey(), fromJson(entry.getValue().getConfigJson()));
        }
        return result;
    }

    private List<SiteConfig> rows(int status) {
        return siteConfigMapper.selectList(Wrappers.<SiteConfig>lambdaQuery()
                .eq(SiteConfig::getStatus, status)
                .eq(SiteConfig::getDeleted, false)
                .orderByAsc(SiteConfig::getVersionNo));
    }

    private List<SiteConfig> draftRows(int version) {
        return siteConfigMapper.selectList(Wrappers.<SiteConfig>lambdaQuery()
                .eq(SiteConfig::getVersionNo, version)
                .eq(SiteConfig::getStatus, STATUS_DRAFT)
                .eq(SiteConfig::getDeleted, false));
    }

    private int publishedVersion() {
        return rows(STATUS_PUBLISHED).stream().mapToInt(SiteConfigService::versionOf).max().orElse(0);
    }

    /**
     * 草稿版本号：还留着草稿行就用它自己的版本；没有草稿就是"当前最大版本 + 1"。
     *
     * <p>发布之后草稿行变成已发布行，所以下一次保存会自然落在新版本上——版本号不会重复。</p>
     */
    private int draftVersion() {
        List<SiteConfig> drafts = rows(STATUS_DRAFT);
        if (!drafts.isEmpty()) {
            return drafts.stream().mapToInt(SiteConfigService::versionOf).max().orElse(1);
        }
        List<SiteConfig> all = siteConfigMapper.selectList(Wrappers.<SiteConfig>lambdaQuery()
                .eq(SiteConfig::getDeleted, false));
        return all.stream().mapToInt(SiteConfigService::versionOf).max().orElse(0) + 1;
    }

    private String publishedTime() {
        return rows(STATUS_PUBLISHED).stream()
                .map(SiteConfig::getPublishedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .map(TIME_FORMAT::format)
                .orElse(null);
    }

    private String draftUpdateTime() {
        return rows(STATUS_DRAFT).stream()
                .map(SiteConfig::getUpdateTime)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .map(TIME_FORMAT::format)
                .orElse(null);
    }

    private String draftEditor() {
        return rows(STATUS_DRAFT).stream()
                .map(SiteConfig::getEditor)
                .filter(Objects::nonNull)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    /** 保存一个区块的草稿：同一版本已有就更新，没有就插入 */
    private void upsertDraft(String sectionKey, int version, Object content, String operator, LocalDateTime now) {
        SiteConfig existing = siteConfigMapper.selectOne(Wrappers.<SiteConfig>lambdaQuery()
                .eq(SiteConfig::getSectionKey, sectionKey)
                .eq(SiteConfig::getVersionNo, version)
                .eq(SiteConfig::getStatus, STATUS_DRAFT)
                .eq(SiteConfig::getDeleted, false)
                .last("LIMIT 1"));
        String json = toJson(content == null ? new LinkedHashMap<>() : content);
        if (existing == null) {
            siteConfigMapper.insert(SiteConfig.builder()
                    .id(idGenerator.nextId())
                    .sectionKey(sectionKey)
                    .configJson(json)
                    .versionNo(version)
                    .status(STATUS_DRAFT)
                    .createTime(now)
                    .updateTime(now)
                    .creator(operator)
                    .editor(operator)
                    .deleted(false)
                    .build());
            return;
        }
        SiteConfig update = new SiteConfig();
        update.setId(existing.getId());
        update.setConfigJson(json);
        update.setUpdateTime(now);
        update.setEditor(operator);
        siteConfigMapper.updateById(update);
    }

    /** 入参校验：只收已知区块、只收对象或数组、文案与总体积设上限 */
    private Map<String, Object> validate(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new BizException(40001, "没有要保存的内容");
        }
        List<String> unknown = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            String key = entry.getKey();
            if (!SECTION_KEYS.contains(key)) {
                unknown.add(key);
                continue;
            }
            Object value = entry.getValue();
            if (value != null && !(value instanceof Map) && !(value instanceof List)) {
                throw new BizException(40001, "区块「" + key + "」的内容必须是对象或数组");
            }
            Object content = value == null ? new LinkedHashMap<String, Object>() : value;
            String json = toJson(content);
            if (json.length() > MAX_SECTION_CHARS) {
                throw new BizException(40001, "区块「" + key + "」内容过长（超过 " + MAX_SECTION_CHARS + " 字符）");
            }
            checkTextLength(key, value);
            result.put(key, content);
        }
        if (!unknown.isEmpty()) {
            throw new BizException(40001, "存在不认识的区块：" + String.join("、", unknown)
                    + "（可用区块：" + String.join("、", SECTION_KEYS) + "）");
        }
        if (result.isEmpty()) {
            throw new BizException(40001, "没有要保存的内容");
        }
        return result;
    }

    private void checkTextLength(String sectionKey, Object value) {
        if (value instanceof String text) {
            if (text.length() > MAX_TEXT_CHARS) {
                throw new BizException(40001,
                        "区块「" + sectionKey + "」里有超过 " + MAX_TEXT_CHARS + " 字的文案，请精简");
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> checkTextLength(sectionKey, item));
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> checkTextLength(sectionKey, item));
        }
    }

    /** 草稿与已发布是否一致：按区块逐个比 JSON，比整体比更稳（键顺序无关） */
    private boolean sameSections(Map<String, Object> left, Map<String, Object> right) {
        for (String key : SECTION_KEYS) {
            if (!toJson(left.get(key)).equals(toJson(right.get(key)))) {
                return false;
            }
        }
        return true;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("序列化官网配置失败：{}", e.getMessage());
            return "null";
        }
    }

    private Object fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("解析 site_config.config_json 失败：{}", e.getMessage());
            return new LinkedHashMap<String, Object>();
        }
    }

    private static int versionOf(SiteConfig row) {
        return row.getVersionNo() == null ? 0 : row.getVersionNo();
    }
}
