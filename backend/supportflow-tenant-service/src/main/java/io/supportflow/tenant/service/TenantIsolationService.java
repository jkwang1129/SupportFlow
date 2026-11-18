package io.supportflow.tenant.service;

import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.audit.AuditLogClient;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.tenant.dto.QuotaBody;
import io.supportflow.tenant.dto.SecurityReport;
import io.supportflow.tenant.dto.TenantDetailVO;
import io.supportflow.tenant.dto.TenantListItemVO;
import io.supportflow.tenant.dto.TenantNameVO;
import io.supportflow.tenant.dto.TenantOverviewVO;
import io.supportflow.tenant.dto.TenantPageVO;
import io.supportflow.tenant.dto.TenantQuotaVO;
import io.supportflow.tenant.dto.TenantSecurityEventVO;
import io.supportflow.tenant.dto.UpgradeHistoryVO;
import io.supportflow.tenant.dto.UsageReport;
import io.supportflow.tenant.entity.Tenant;
import io.supportflow.tenant.entity.TenantQuota;
import io.supportflow.tenant.entity.TenantSchemaUpgrade;
import io.supportflow.tenant.entity.TenantSecurityEvent;
import io.supportflow.tenant.internal.TenantSchemaUpgradeClient;
import io.supportflow.tenant.mapper.TenantIsolationMapper;
import io.supportflow.tenant.mapper.TenantMapper;
import io.supportflow.tenant.mapper.TenantQuotaMapper;
import io.supportflow.tenant.mapper.TenantSchemaUpgradeMapper;
import io.supportflow.tenant.mapper.TenantSecurityEventMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 租户隔离与治理（平台控制面）：
 * 1. 行级安全状态：汇总"哪些表开了 RLS"（真正的策略在 customer_db 的 RLS 脚本里）；
 * 2. 独立库升级：下发到 customer-service 执行（脚本副本在那边），把结果写进
 * tenant_schema_upgrade 并更新 tenant.db_version；
 * 3. 租户级限流：每个租户的 QPS / 日额度（tenant_quota），网关按它限流，
 * 用量由网关批量上报到 tenant_usage_daily。
 */
@Service
public class TenantIsolationService {

    private static final Logger log = LoggerFactory.getLogger(TenantIsolationService.class);

    /** 隔离模式：1-共享库、2-独立 Schema、3-独立实例 */
    public static final int MODE_SHARED = 1;
    public static final int MODE_SCHEMA = 2;
    public static final int MODE_DEDICATED = 3;

    /** 租户状态：1-正常、2-冻结 */
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_FROZEN = 2;

    /** 独立 Schema 名规范（与数据面的校验保持一致） */
    private static final Pattern SCHEMA_NAME = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

    /** 升级记录的默认查询条数 */
    private static final int HISTORY_LIMIT = 10;

    private final TenantMapper tenantMapper;
    private final TenantQuotaMapper quotaMapper;
    private final TenantSchemaUpgradeMapper upgradeMapper;
    private final TenantSecurityEventMapper securityMapper;
    private final TenantIsolationMapper isolationMapper;
    private final TenantSchemaUpgradeClient upgradeClient;
    private final SnowflakeIdGenerator idGenerator;
    /** 审计日志：租户治理的四个写操作（配额 / 隔离模式 / 冻结 / 升级）都要留痕 */
    private final AuditLogClient auditLogClient;

    /** 审计用的模块名（与平台页筛选器一致） */
    private static final String AUDIT_MODULE = "租户管理";

    public TenantIsolationService(TenantMapper tenantMapper,
                                  TenantQuotaMapper quotaMapper,
                                  TenantSchemaUpgradeMapper upgradeMapper,
                                  TenantSecurityEventMapper securityMapper,
                                  TenantIsolationMapper isolationMapper,
                                  TenantSchemaUpgradeClient upgradeClient,
                                  SnowflakeIdGenerator idGenerator,
                                  AuditLogClient auditLogClient) {
        this.tenantMapper = tenantMapper;
        this.quotaMapper = quotaMapper;
        this.upgradeMapper = upgradeMapper;
        this.securityMapper = securityMapper;
        this.isolationMapper = isolationMapper;
        this.upgradeClient = upgradeClient;
        this.idGenerator = idGenerator;
        this.auditLogClient = auditLogClient;
    }

    // ------------------------------------------------------------------ 列表与详情

    public TenantPageVO page(Integer status, Integer isolationMode, String keyword,
                             int page, int pageSize) {
        long total = isolationMapper.countTenantList(status, isolationMode, keyword);
        List<Map<String, Object>> rows = isolationMapper.selectTenantList(
                status, isolationMode, keyword, pageSize, (long) (page - 1) * pageSize);
        List<TenantListItemVO> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            list.add(listItemOf(row));
        }
        return new TenantPageVO(total, page, pageSize, list);
    }

    public TenantOverviewVO overview() {
        int latest = latestVersion();
        Map<String, Object> raw = isolationMapper.selectOverview(latest);
        return new TenantOverviewVO(intValue(raw == null ? null : raw.get("total")),
                intValue(raw == null ? null : raw.get("active")),
                intValue(raw == null ? null : raw.get("dedicated")),
                intValue(raw == null ? null : raw.get("frozen")),
                intValue(raw == null ? null : raw.get("version_behind")),
                longValue(raw == null ? null : raw.get("today_rejected")),
                latest);
    }

    public TenantDetailVO detail(String tenantCode) {
        Map<String, Object> detail = isolationMapper.selectTenantDetail(tenantCode);
        if (detail == null) {
            throw new BizException(40401, "租户不存在：" + tenantCode);
        }
        String schema = targetSchemaOf(detail);
        int currentVersion;
        int latestVersion;
        List<?> pending;
        boolean reachable;
        Map<String, Object> status = upgradeClient.status(tenantCode, schema);
        if (status != null) {
            currentVersion = intValue(status.get("currentVersion"));
            latestVersion = intValue(status.get("latestVersion"));
            pending = status.get("pending") instanceof List<?> list ? list : List.of();
            reachable = true;
        } else {
            // 数据面连不上：用控制面记的版本兜底，并明说"这是兜底值"
            currentVersion = intValue(detail.get("db_version"));
            latestVersion = latestVersion();
            pending = List.of();
            reachable = false;
        }
        return new TenantDetailVO(stringOf(detail.get("tenant_code")),
                intOrNull(detail.get("status")), intOrNull(detail.get("isolation_mode")),
                stringOf(detail.get("schema_name")),
                intOrNull(detail.get("db_version")), localDateTimeOrNull(detail.get("upgraded_at")),
                localDateTimeOrNull(detail.get("expire_time")),
                localDateTimeOrNull(detail.get("create_time")),
                stringOf(detail.get("company_name")), stringOf(detail.get("industry")),
                stringOf(detail.get("license_no")), stringOf(detail.get("legal_person")),
                stringOf(detail.get("contact_name")), intOrNull(detail.get("qps_limit")),
                intOrNull(detail.get("daily_request_limit")),
                intOrNull(detail.get("daily_session_limit")),
                intOrNull(detail.get("ai_daily_limit")),
                detail.get("quota_enabled") instanceof Boolean b ? b : null,
                stringOf(detail.get("quota_remark")), intOrNull(detail.get("today_requests")),
                intOrNull(detail.get("today_rejected")), intOrNull(detail.get("today_sessions")),
                intOrNull(detail.get("today_ai_calls")), currentVersion, latestVersion, pending,
                reachable,
                upgradeHistoryOf(isolationMapper.selectUpgradeHistory(tenantCode, HISTORY_LIMIT)),
                securityEventsOf(isolationMapper.selectSecurityEvents(tenantCode, HISTORY_LIMIT)));
    }

    public List<TenantSecurityEventVO> securityEvents(String tenantCode, int limit) {
        return securityEventsOf(isolationMapper.selectSecurityEvents(tenantCode, limit));
    }

    /**
     * 租户目录：编码 → 企业名称。
     *
     * <p>平台侧跨租户的页面（收款流水等）只拿得到 tenant_code，企业名在另一个库，
     * 跨库 join 不了，所以由这里提供一份目录，页面拉一次做成 map。</p>
     */
    public List<TenantNameVO> tenantNames() {
        List<TenantNameVO> list = new ArrayList<>();
        for (Map<String, Object> row : isolationMapper.selectTenantNames()) {
            list.add(new TenantNameVO(stringOf(row.get("tenant_code")),
                    stringOf(row.get("company_name"))));
        }
        return list;
    }

    // ------------------------------------------------------------------ 配额与状态

    /** 调整租户限流配额（null = 不改这一项；0 = 不限） */
    @Transactional
    public TenantDetailVO updateQuota(LoginUser user, String tenantCode, QuotaBody body) {
        Tenant tenant = requireTenant(tenantCode);
        requireNonNegative(body.qpsLimit(), "QPS 上限");
        requireNonNegative(body.dailyRequestLimit(), "每天请求上限");
        requireNonNegative(body.dailySessionLimit(), "每天会话上限");
        requireNonNegative(body.aiDailyLimit(), "每天 AI 调用上限");

        TenantQuota quota = quotaOf(tenant.getTenantCode());
        LocalDateTime now = LocalDateTime.now();
        if (quota == null) {
            quotaMapper.insert(TenantQuota.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenant.getTenantCode())
                    .qpsLimit(body.qpsLimit() == null ? 20 : body.qpsLimit())
                    .dailyRequestLimit(body.dailyRequestLimit() == null ? 200000 : body.dailyRequestLimit())
                    .dailySessionLimit(body.dailySessionLimit() == null ? 20000 : body.dailySessionLimit())
                    .aiDailyLimit(body.aiDailyLimit() == null ? 5000 : body.aiDailyLimit())
                    .enabled(body.enabled() == null || body.enabled())
                    .remark(body.remark())
                    .createTime(now)
                    .updateTime(now)
                    .creator(user == null ? "platform" : user.name())
                    .deleted(false)
                    .build());
        } else {
            if (body.qpsLimit() != null) {
                quota.setQpsLimit(body.qpsLimit());
            }
            if (body.dailyRequestLimit() != null) {
                quota.setDailyRequestLimit(body.dailyRequestLimit());
            }
            if (body.dailySessionLimit() != null) {
                quota.setDailySessionLimit(body.dailySessionLimit());
            }
            if (body.aiDailyLimit() != null) {
                quota.setAiDailyLimit(body.aiDailyLimit());
            }
            if (body.enabled() != null) {
                quota.setEnabled(body.enabled());
            }
            if (body.remark() != null) {
                quota.setRemark(body.remark());
            }
            quota.setUpdateTime(now);
            quota.setEditor(user == null ? "platform" : user.name());
            quotaMapper.updateById(quota);
        }
        log.info("租户配额已调整 tenant={} by={} qps={} daily={}",
                tenant.getTenantCode(), user == null ? "platform" : user.name(),
                body.qpsLimit(), body.dailyRequestLimit());
        auditLogClient.record(user, AUDIT_MODULE, "调整限流配额", "租户", tenant.getTenantCode(), true,
                "QPS=" + body.qpsLimit() + "，日请求=" + body.dailyRequestLimit()
                        + "，日会话=" + body.dailySessionLimit() + "，日 AI 调用=" + body.aiDailyLimit());
        return detail(tenant.getTenantCode());
    }

    /**
     * 切换隔离模式。
     *
     * <p>只做"登记 + 校验"，不做数据搬迁：把共享库的数据搬到独立 Schema（或反过来）
     * 是运维动作（导出 → 建 schema → 导入 → 校验），代码里假搬一次才是真的危险。
     * 所以这里要求：切到独立 Schema 必须带 schema 名；切到独立实例要联系运维（直接拒）。</p>
     */
    @Transactional
    public TenantDetailVO switchIsolationMode(LoginUser user, String tenantCode,
                                              int targetMode, String schemaName) {
        Tenant tenant = requireTenant(tenantCode);
        if (targetMode != MODE_SHARED && targetMode != MODE_SCHEMA && targetMode != MODE_DEDICATED) {
            throw new BizException(40001, "隔离模式只能是 1-共享库 / 2-独立 Schema / 3-独立实例");
        }
        Integer current = tenant.getIsolationMode();
        if (current != null && current == targetMode) {
            return detail(tenant.getTenantCode());
        }
        if (targetMode == MODE_DEDICATED) {
            throw new BizException(40001, "独立实例需要运维单独部署数据库后手工切换（不让接口直接改）");
        }
        String schema = null;
        if (targetMode == MODE_SCHEMA) {
            schema = schemaName == null ? null : schemaName.trim();
            if (schema == null || !SCHEMA_NAME.matcher(schema).matches()) {
                throw new BizException(40001, "切到独立 Schema 必须给出合法 schema 名"
                        + "（小写字母/数字/下划线，字母或下划线开头）");
            }
        }
        Tenant update = new Tenant();
        update.setId(tenant.getId());
        update.setIsolationMode(targetMode);
        update.setSchemaName(schema);
        update.setUpdateTime(LocalDateTime.now());
        update.setEditor(user == null ? "platform" : user.name());
        tenantMapper.updateById(update);
        log.info("租户隔离模式切换 tenant={} {} → {} schema={} by={}",
                tenant.getTenantCode(), current, targetMode, schema,
                user == null ? "platform" : user.name());
        auditLogClient.record(user, AUDIT_MODULE, "切换隔离模式", "租户", tenant.getTenantCode(), true,
                "隔离模式 " + current + " → " + targetMode + "，schema=" + schema);
        return detail(tenant.getTenantCode());
    }

    /** 冻结 / 解冻（冻结后网关按租户状态直接拒绝，不是限流） */
    @Transactional
    public TenantDetailVO freeze(LoginUser user, String tenantCode, boolean freeze) {
        Tenant tenant = requireTenant(tenantCode);
        int target = freeze ? STATUS_FROZEN : STATUS_ACTIVE;
        if (tenant.getStatus() != null && tenant.getStatus() == target) {
            return detail(tenant.getTenantCode());
        }
        Tenant update = new Tenant();
        update.setId(tenant.getId());
        update.setStatus(target);
        update.setUpdateTime(LocalDateTime.now());
        update.setEditor(user == null ? "platform" : user.name());
        tenantMapper.updateById(update);
        log.info("租户{} tenant={} by={}", freeze ? "冻结" : "解冻",
                tenant.getTenantCode(), user == null ? "platform" : user.name());
        // 冻结一停就是"整家企业进不来"，审计里必须留一条
        auditLogClient.record(user, AUDIT_MODULE, freeze ? "冻结租户" : "解冻租户",
                "租户", tenant.getTenantCode(), true,
                freeze ? "冻结后该租户所有请求都会被网关拒绝" : "恢复该租户的访问");
        return detail(tenant.getTenantCode());
    }

    // ------------------------------------------------------------------ 独立库升级

    /**
     * 发起升级：控制面下发 → 数据面执行 → 两边各留一条记录。
     *
     * <p>先写一条"进行中"的记录再执行：万一进程中途挂了，历史里也能看到
     * "有一次升级没走完"，而不是什么都没有。失败时把原因写进记录并抛给调用方。</p>
     */
    public TenantDetailVO upgrade(LoginUser user, String tenantCode, Integer toVersion) {
        Tenant tenant = requireTenant(tenantCode);
        Map<String, Object> detailBefore = isolationMapper.selectTenantDetail(tenant.getTenantCode());
        String schema = targetSchemaOf(detailBefore);
        int fromVersion = intValue(detailBefore == null ? null : detailBefore.get("db_version"));

        TenantSchemaUpgrade record = TenantSchemaUpgrade.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant.getTenantCode())
                .targetSchema(schema)
                .fromVersion(fromVersion)
                .toVersion(toVersion == null ? 0 : toVersion)
                .status(1)
                .scriptCount(0)
                .operatorId(user == null ? null : user.userId())
                .operatorName(user == null ? "platform" : user.name())
                .startedAt(LocalDateTime.now())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        upgradeMapper.insert(record);

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> result = upgradeClient.upgrade(tenant.getTenantCode(), schema, toVersion);
            int to = result == null ? fromVersion : intValue(result.get("toVersion"));
            List<?> applied = result == null ? List.of() : (List<?>) result.get("appliedScripts");
            record.setStatus(2);
            record.setFromVersion(result == null ? fromVersion : intValue(result.get("fromVersion")));
            record.setToVersion(to);
            record.setScriptCount(applied == null ? 0 : applied.size());
            record.setAppliedScripts(applied == null ? null : String.join(",", toStringList(applied)));
            record.setMessage(result == null ? "数据面未返回结果" : stringValue(result.get("message")));
            record.setDurationMs((int) (System.currentTimeMillis() - start));
            record.setFinishedAt(LocalDateTime.now());
            record.setUpdateTime(LocalDateTime.now());
            upgradeMapper.updateById(record);

            Tenant update = new Tenant();
            update.setId(tenant.getId());
            update.setDbVersion(to);
            update.setUpgradedAt(LocalDateTime.now());
            update.setUpdateTime(LocalDateTime.now());
            tenantMapper.updateById(update);
            log.info("租户库升级完成 tenant={} v{} → v{} 执行 {} 个脚本",
                    tenant.getTenantCode(), record.getFromVersion(), to,
                    record.getScriptCount());
            auditLogClient.record(user, AUDIT_MODULE, "升级租户库", "租户", tenant.getTenantCode(), true,
                    "库版本 v" + record.getFromVersion() + " → v" + to
                            + "，执行 " + record.getScriptCount() + " 个脚本");
            return detail(tenant.getTenantCode());
        } catch (Exception e) {
            record.setStatus(3);
            record.setMessage(shorten(e.getMessage()));
            record.setDurationMs((int) (System.currentTimeMillis() - start));
            record.setFinishedAt(LocalDateTime.now());
            record.setUpdateTime(LocalDateTime.now());
            upgradeMapper.updateById(record);
            log.error("租户库升级失败 tenant={} error={}", tenant.getTenantCode(), e.getMessage());
            throw new BizException(50002, "升级失败：" + shorten(e.getMessage())
                    + "（已写入升级历史，修好后可重试）");
        }
    }

    // ------------------------------------------------------------------ 网关内部接口

    /** 网关取限额（缓存 30 秒，见网关侧说明） */
    public TenantQuotaVO quotaForGateway(String tenantCode) {
        Tenant tenant = tenantMapper.selectOne(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getTenantCode, tenantCode)
                .eq(Tenant::getDeleted, false));
        if (tenant == null) {
            return TenantQuotaVO.unknown(tenantCode);
        }
        TenantQuota quota = quotaOf(tenantCode);
        return new TenantQuotaVO(tenantCode, true, tenant.getStatus(), tenant.getIsolationMode(),
                quota == null || !Boolean.FALSE.equals(quota.getEnabled()),
                quota == null ? 20 : quota.getQpsLimit(),
                quota == null ? 200000 : quota.getDailyRequestLimit(),
                quota == null ? 20000 : quota.getDailySessionLimit(),
                quota == null ? 5000 : quota.getAiDailyLimit());
    }

    /** 网关批量上报用量（每 5 秒一次，累加到当天） */
    @Transactional
    public void reportUsage(List<UsageReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (UsageReport report : reports) {
            if (report == null || report.tenantCode() == null || report.tenantCode().isBlank()) {
                continue;
            }
            isolationMapper.upsertUsage(idGenerator.nextId(), report.tenantCode(),
                    report.statDate() == null ? LocalDate.now() : report.statDate(),
                    report.requestCount(), report.rejectedCount(),
                    report.sessionCount(), report.aiCallCount());
        }
        log.debug("用量上报 {} 条，时间 {}", reports.size(), now);
    }

    /** 网关上报告警（篡改租户头 / 跨租户访问 / 被限流） */
    @Transactional
    public void reportSecurityEvent(SecurityReport report) {
        if (report == null || report.tenantCode() == null || report.tenantCode().isBlank()) {
            return;
        }
        securityMapper.insert(TenantSecurityEvent.builder()
                .id(idGenerator.nextId())
                .tenantCode(report.tenantCode())
                .eventType(report.eventType() == null ? 2 : report.eventType())
                .detail(shorten(report.detail()))
                .requestUri(report.requestUri())
                .requestMethod(report.requestMethod())
                .clientIp(report.clientIp())
                .tokenTenant(report.tokenTenant())
                .operatorName(report.operatorName())
                .eventTime(LocalDateTime.now())
                .createTime(LocalDateTime.now())
                .build());
    }

    // ------------------------------------------------------------------ 内部工具

    /**
     * 租户列表行：SQL 返回的是下划线列名的 Map，这里显式转成实体。
     *
     * <p>以前是用 {@code camelize} 把整行键名批量转驼峰再补 4 个派生字段——
     * 那种写法列名改了、类型变了都发现不了；现在字段与类型都在实体上钉死了。</p>
     */
    private static TenantListItemVO listItemOf(Map<String, Object> row) {
        return new TenantListItemVO(stringOf(row.get("tenant_code")), intOrNull(row.get("status")),
                intOrNull(row.get("isolation_mode")), stringOf(row.get("schema_name")),
                intOrNull(row.get("db_version")), localDateTimeOrNull(row.get("upgraded_at")),
                localDateTimeOrNull(row.get("expire_time")),
                localDateTimeOrNull(row.get("create_time")), stringOf(row.get("company_name")),
                stringOf(row.get("industry")), intOrNull(row.get("qps_limit")),
                intOrNull(row.get("daily_request_limit")),
                row.get("quota_enabled") instanceof Boolean b ? b : null,
                intOrNull(row.get("today_requests")), intOrNull(row.get("today_rejected")),
                longOrNull(row.get("upgrade_count")),
                localDateTimeOrNull(row.get("last_upgrade_at")),
                longOrNull(row.get("today_security_events")),
                isolationModeText(intValue(row.get("isolation_mode"))),
                statusText(intValue(row.get("status"))));
    }

    private static List<TenantSecurityEventVO> securityEventsOf(List<Map<String, Object>> rows) {
        List<TenantSecurityEventVO> list = new ArrayList<>(rows == null ? 0 : rows.size());
        if (rows == null) {
            return list;
        }
        for (Map<String, Object> row : rows) {
            list.add(new TenantSecurityEventVO(longOrNull(row.get("id")),
                    stringOf(row.get("tenant_code")), intOrNull(row.get("event_type")),
                    stringOf(row.get("detail")), stringOf(row.get("request_uri")),
                    stringOf(row.get("request_method")), stringOf(row.get("client_ip")),
                    stringOf(row.get("token_tenant")), stringOf(row.get("operator_name")),
                    localDateTimeOrNull(row.get("event_time"))));
        }
        return list;
    }

    private static List<UpgradeHistoryVO> upgradeHistoryOf(List<Map<String, Object>> rows) {
        List<UpgradeHistoryVO> list = new ArrayList<>(rows == null ? 0 : rows.size());
        if (rows == null) {
            return list;
        }
        for (Map<String, Object> row : rows) {
            list.add(new UpgradeHistoryVO(longOrNull(row.get("id")),
                    stringOf(row.get("target_schema")), intOrNull(row.get("from_version")),
                    intOrNull(row.get("to_version")), intOrNull(row.get("status")),
                    intOrNull(row.get("script_count")), stringOf(row.get("applied_scripts")),
                    stringOf(row.get("message")), intOrNull(row.get("duration_ms")),
                    stringOf(row.get("operator_name")),
                    localDateTimeOrNull(row.get("started_at")),
                    localDateTimeOrNull(row.get("finished_at"))));
        }
        return list;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Long longOrNull(Object value) {
        return value instanceof Number number ? number.longValue() : null;
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

    private int latestVersion() {
        Map<String, Object> catalog = upgradeClient.catalog();
        return catalog == null ? 0 : intValue(catalog.get("latestVersion"));
    }

    private TenantQuota quotaOf(String tenantCode) {
        return quotaMapper.selectOne(Wrappers.<TenantQuota>lambdaQuery()
                .eq(TenantQuota::getTenantCode, tenantCode)
                .eq(TenantQuota::getDeleted, false));
    }

    private Tenant requireTenant(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new BizException(40001, "缺少租户编码");
        }
        Tenant tenant = tenantMapper.selectOne(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getTenantCode, tenantCode)
                .eq(Tenant::getDeleted, false));
        if (tenant == null) {
            throw new BizException(40401, "租户不存在：" + tenantCode);
        }
        return tenant;
    }

    private static String targetSchemaOf(Map<String, Object> detail) {
        String schema = detail == null ? null : stringValue(detail.get("schema_name"));
        return schema == null || schema.isBlank() ? "public" : schema;
    }

    private static void requireNonNegative(Integer value, String label) {
        if (value != null && value < 0) {
            throw new BizException(40001, label + "不能为负数（0 表示不限）");
        }
    }

    private static String isolationModeText(int mode) {
        return switch (mode) {
            case MODE_SCHEMA -> "独立 Schema";
            case MODE_DEDICATED -> "独立实例";
            default -> "共享库";
        };
    }

    private static String statusText(int status) {
        return switch (status) {
            case STATUS_FROZEN -> "已冻结";
            case 3 -> "已到期";
            case 4 -> "已注销";
            default -> "正常";
        };
    }

    private static List<String> toStringList(List<?> values) {
        List<String> list = new ArrayList<>(values.size());
        for (Object value : values) {
            list.add(String.valueOf(value));
        }
        return list;
    }

    private static String shorten(String message) {
        if (message == null) {
            return null;
        }
        String flat = message.replaceAll("[\\r\\n]+", " ").trim();
        return flat.length() > 400 ? flat.substring(0, 400) + "…" : flat;
    }

    private static int intValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long longValue(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
