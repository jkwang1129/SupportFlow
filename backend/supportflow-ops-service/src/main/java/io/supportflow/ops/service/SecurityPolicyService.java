package io.supportflow.ops.service;

import io.supportflow.common.audit.AuditLogClient;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.ops.dto.ApiKeyBody;
import io.supportflow.ops.dto.ApiKeyListVO;
import io.supportflow.ops.dto.ApiKeyStatusVO;
import io.supportflow.ops.dto.ApiKeySummaryVO;
import io.supportflow.ops.dto.ApiKeyVO;
import io.supportflow.ops.dto.CreatedApiKeyVO;
import io.supportflow.ops.dto.LoginPolicyVO;
import io.supportflow.ops.dto.PolicyBody;
import io.supportflow.ops.dto.SecurityPolicyVO;
import io.supportflow.ops.entity.ApiKey;
import io.supportflow.ops.entity.SecurityPolicy;
import io.supportflow.ops.mapper.ApiKeyMapper;
import io.supportflow.ops.mapper.SecurityPolicyMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 安全中心的两块：安全策略 + API 密钥。
 *
 * <p>两条原则：</p>
 * <ol>
 *   <li><b>策略必须有执行点</b>——登录失败锁定在 user-service 登录链路生效、登录 IP 白名单在网关生效、
 *       导出审批在导出接口生效、水印与空闲登出在前端生效、双因素（登录二次验证）同样在
 *       user-service 登录链路生效；没有执行点的事就不做成开关；</li>
 *   <li><b>密钥只存哈希</b>——明文只在生成时回显一次，库里存 SHA-256，页面靠前缀辨认。</li>
 * </ol>
 */
@Service
public class SecurityPolicyService {

    /** 双因素覆盖范围：1-仅平台账号与企业管理员（默认，打扰最小）、2-全部账号（含坐席） */
    public static final int TWO_FACTOR_SCOPE_MANAGERS = 1;
    public static final int TWO_FACTOR_SCOPE_ALL = 2;

    private static final Logger log = LoggerFactory.getLogger(SecurityPolicyService.class);

    /** 审计模块名（与平台页筛选器一致） */
    private static final String MODULE = "安全中心";

    private static final String KEY_PREFIX = "yk_";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SecurityPolicyMapper policyMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final AuditLogClient auditLogClient;

    public SecurityPolicyService(SecurityPolicyMapper policyMapper,
                                 ApiKeyMapper apiKeyMapper,
                                 SnowflakeIdGenerator idGenerator,
                                 AuditLogClient auditLogClient) {
        this.policyMapper = policyMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.idGenerator = idGenerator;
        this.auditLogClient = auditLogClient;
    }

    // ------------------------------------------------------------------ 安全策略

    /** 读策略；没配过给一套默认值（不写库，等运营点保存才落） */
    public SecurityPolicyVO policy() {
        SecurityPolicy current = currentPolicy();
        return new SecurityPolicyVO(
                current == null ? 5 : intValue(current.getLoginFailThreshold(), 5),
                current == null ? 15 : intValue(current.getLoginLockMinutes(), 15),
                current != null && Boolean.TRUE.equals(current.getIpWhitelistEnabled()),
                current == null || current.getIpWhitelist() == null ? "127.0.0.1" : current.getIpWhitelist(),
                current == null || !Boolean.FALSE.equals(current.getExportNeedApproval()),
                current == null || !Boolean.FALSE.equals(current.getWatermarkEnabled()),
                current == null ? 120 : intValue(current.getSessionTimeoutMinutes(), 120),
                current != null && Boolean.TRUE.equals(current.getTwoFactorRequired()),
                current == null || current.getTwoFactorScope() == null
                        ? TWO_FACTOR_SCOPE_MANAGERS
                        : intValue(current.getTwoFactorScope(), TWO_FACTOR_SCOPE_MANAGERS),
                current != null,
                current == null || current.getUpdateTime() == null
                        ? null : current.getUpdateTime().format(TIME_FORMAT),
                current == null ? null : current.getEditor());
    }

    /** 导出是否需要审批（导出接口每次导前问一次） */
    public boolean exportNeedApproval() {
        SecurityPolicy current = currentPolicy();
        return current == null || !Boolean.FALSE.equals(current.getExportNeedApproval());
    }

    /** 登录相关策略（user-service 登录链路与网关 IP 白名单用） */
    public LoginPolicyVO loginPolicy() {
        SecurityPolicy current = currentPolicy();
        // 双因素也走登录策略：user-service 的登录链路一次拉齐"锁定 / 白名单 / 双因素"，
        // 不必为了一个开关再开一条内部接口（多一条链路就多一个会忘的地方）
        return new LoginPolicyVO(
                current == null ? 5 : intValue(current.getLoginFailThreshold(), 5),
                current == null ? 15 : intValue(current.getLoginLockMinutes(), 15),
                current != null && Boolean.TRUE.equals(current.getIpWhitelistEnabled()),
                current == null || current.getIpWhitelist() == null ? "127.0.0.1" : current.getIpWhitelist(),
                current != null && Boolean.TRUE.equals(current.getTwoFactorRequired()),
                current == null || current.getTwoFactorScope() == null
                        ? TWO_FACTOR_SCOPE_MANAGERS
                        : intValue(current.getTwoFactorScope(), TWO_FACTOR_SCOPE_MANAGERS));
    }

    /** 保存策略（upsert + 审计） */
    @Transactional
    public SecurityPolicyVO savePolicy(LoginUser operator, PolicyBody body) {
        if (body == null) {
            throw new BizException(40001, "缺少策略内容");
        }
        int threshold = clamp(body.loginFailThreshold() == null ? 5 : body.loginFailThreshold(), 0, 20);
        int lockMinutes = clamp(body.loginLockMinutes() == null ? 15 : body.loginLockMinutes(), 0, 1440);
        int timeout = clamp(body.sessionTimeoutMinutes() == null ? 120
                : body.sessionTimeoutMinutes(), 5, 1440);
        String whitelist = normalizeWhitelist(body.ipWhitelist());
        boolean whitelistEnabled = Boolean.TRUE.equals(body.ipWhitelistEnabled());
        if (whitelistEnabled && whitelist.isBlank()) {
            throw new BizException(40001, "开了 IP 白名单就必须填至少一个 IP，否则管理端会被全部挡在门外");
        }
        boolean exportApproval = !Boolean.FALSE.equals(body.exportNeedApproval());
        boolean watermark = !Boolean.FALSE.equals(body.watermarkEnabled());
        boolean twoFactor = Boolean.TRUE.equals(body.twoFactorRequired());
        int twoFactorScope = clamp(body.twoFactorScope() == null
                ? TWO_FACTOR_SCOPE_MANAGERS : body.twoFactorScope(),
                TWO_FACTOR_SCOPE_MANAGERS, TWO_FACTOR_SCOPE_ALL);
        LocalDateTime now = LocalDateTime.now();
        String operatorName = operator == null ? "系统" : operator.name();
        String finalWhitelist = whitelist.isBlank() ? "127.0.0.1" : whitelist;
        SecurityPolicy current = currentPolicy();
        if (current == null) {
            policyMapper.insert(SecurityPolicy.builder()
                    .id(idGenerator.nextId())
                    .loginFailThreshold((short) threshold)
                    .loginLockMinutes((short) lockMinutes)
                    .ipWhitelistEnabled(whitelistEnabled)
                    .ipWhitelist(finalWhitelist)
                    .exportNeedApproval(exportApproval)
                    .watermarkEnabled(watermark)
                    .sessionTimeoutMinutes((short) timeout)
                    .twoFactorRequired(twoFactor)
                    .twoFactorScope((short) twoFactorScope)
                    .createTime(now)
                    .updateTime(now)
                    .creator(operatorName)
                    .editor(operatorName)
                    .deleted(false)
                    .build());
        } else {
            SecurityPolicy update = new SecurityPolicy();
            update.setId(current.getId());
            update.setLoginFailThreshold((short) threshold);
            update.setLoginLockMinutes((short) lockMinutes);
            update.setIpWhitelistEnabled(whitelistEnabled);
            update.setIpWhitelist(finalWhitelist);
            update.setExportNeedApproval(exportApproval);
            update.setWatermarkEnabled(watermark);
            update.setSessionTimeoutMinutes((short) timeout);
            update.setTwoFactorRequired(twoFactor);
            update.setTwoFactorScope((short) twoFactorScope);
            update.setUpdateTime(now);
            update.setEditor(operatorName);
            policyMapper.updateById(update);
        }
        auditLogClient.record(operator, MODULE, "保存安全策略", "策略", "security_policy", true,
                "登录失败锁定 " + threshold + " 次 / " + lockMinutes + " 分钟，IP 白名单="
                        + (whitelistEnabled ? "开（" + finalWhitelist + "）" : "关")
                        + "，导出审批=" + exportApproval + "，水印=" + watermark
                        + "，空闲登出 " + timeout + " 分钟，双因素=" + twoFactor
                        + (twoFactor ? "（范围：" + twoFactorScopeText(twoFactorScope) + "）" : ""));
        return policy();
    }

    /** 双因素覆盖范围文案（1-仅管理员、2-全部账号） */
    public static String twoFactorScopeText(int scope) {
        return scope == TWO_FACTOR_SCOPE_ALL ? "全部账号（含坐席）" : "仅平台账号与企业管理员";
    }

    private SecurityPolicy currentPolicy() {
        return policyMapper.selectOne(Wrappers.<SecurityPolicy>lambdaQuery()
                .eq(SecurityPolicy::getDeleted, false)
                .orderByAsc(SecurityPolicy::getId)
                .last("LIMIT 1"));
    }

    // ------------------------------------------------------------------ API 密钥

    public ApiKeyListVO apiKeys(String tenantCode) {
        String tenant = tenantCode == null || tenantCode.isBlank() ? null : tenantCode.trim();
        List<ApiKey> rows = apiKeyMapper.selectList(Wrappers.<ApiKey>lambdaQuery()
                .eq(ApiKey::getDeleted, false)
                .eq(tenant != null, ApiKey::getTenantCode, tenant)
                .orderByDesc(ApiKey::getCreateTime));
        List<ApiKeyVO> items = new ArrayList<>(rows.size());
        int enabled = 0;
        long calls = 0;
        for (ApiKey row : rows) {
            items.add(new ApiKeyVO(String.valueOf(row.getId()), row.getTenantCode(), row.getKeyName(),
                    row.getKeyPrefix() == null ? "—" : row.getKeyPrefix() + "…",
                    row.getPermissions(), row.getStatus() == null ? null : row.getStatus().intValue(),
                    row.getStatus() != null && row.getStatus() == 1 ? "启用" : "停用",
                    row.getCallCount() == null ? 0L : row.getCallCount(),
                    text(row.getLastUsedAt()), text(row.getExpireTime()),
                    text(row.getCreateTime()), row.getCreator()));
            if (row.getStatus() != null && row.getStatus() == 1) {
                enabled++;
            }
            calls += row.getCallCount() == null ? 0 : row.getCallCount();
        }
        return new ApiKeyListVO(new ApiKeySummaryVO(items.size(), enabled, calls), items);
    }

    /** 生成密钥：明文只在这次响应里回显，库里存哈希 */
    @Transactional
    public CreatedApiKeyVO createApiKey(LoginUser operator, ApiKeyBody body) {
        if (body == null || body.tenantCode() == null || body.tenantCode().isBlank()) {
            throw new BizException(40001, "请选择密钥归属的租户");
        }
        if (body.keyName() == null || body.keyName().isBlank()) {
            throw new BizException(40001, "请填写密钥用途名称");
        }
        String raw = KEY_PREFIX + randomToken();
        String prefix = raw.substring(0, Math.min(12, raw.length()));
        LocalDateTime now = LocalDateTime.now();
        String operatorName = operator == null ? "系统" : operator.name();
        long id = idGenerator.nextId();
        String permissions = body.permissions() == null || body.permissions().isBlank()
                ? "read" : body.permissions().trim();
        apiKeyMapper.insert(ApiKey.builder()
                .id(id)
                .tenantCode(body.tenantCode().trim())
                .keyName(body.keyName().trim())
                .apiKey(sha256(raw))
                .keyPrefix(prefix)
                .permissions(permissions)
                .status((short) 1)
                .callCount(0L)
                .expireTime(parseTime(body.expireTime()))
                .createTime(now)
                .updateTime(now)
                .creator(operatorName)
                .editor(operatorName)
                .deleted(false)
                .build());
        auditLogClient.record(operator, MODULE, "生成 API 密钥", "密钥", String.valueOf(id), true,
                "为租户 " + body.tenantCode().trim() + " 生成密钥「" + body.keyName().trim()
                        + "」（权限 " + permissions + "），前缀 " + prefix);
        return new CreatedApiKeyVO(String.valueOf(id), raw);
    }

    @Transactional
    public ApiKeyStatusVO updateApiKeyStatus(LoginUser operator, long id, int status) {
        if (status != 1 && status != 2) {
            throw new BizException(40001, "只支持启用（1）或停用（2）");
        }
        ApiKey existing = apiKeyMapper.selectById(id);
        if (existing == null || Boolean.TRUE.equals(existing.getDeleted())) {
            throw new BizException(40401, "密钥不存在：" + id);
        }
        ApiKey update = new ApiKey();
        update.setId(id);
        update.setStatus((short) status);
        update.setUpdateTime(LocalDateTime.now());
        update.setEditor(operator == null ? "系统" : operator.name());
        apiKeyMapper.updateById(update);
        auditLogClient.record(operator, MODULE, status == 1 ? "启用 API 密钥" : "停用 API 密钥",
                "密钥", String.valueOf(id), true,
                (status == 1 ? "启用" : "停用") + "密钥「" + existing.getKeyName() + "」（租户 "
                        + existing.getTenantCode() + "）");
        return new ApiKeyStatusVO(String.valueOf(id), status);
    }

    /**
     * 校验密钥（网关调用的内部接口）。
     *
     * @return 命中返回租户编码；未命中 / 停用 / 过期返回 null；命中时累加调用次数与最后使用时间
     */
    @Transactional
    public String verifyApiKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        ApiKey row = apiKeyMapper.selectOne(Wrappers.<ApiKey>lambdaQuery()
                .eq(ApiKey::getApiKey, sha256(raw.trim()))
                .eq(ApiKey::getDeleted, false)
                .last("LIMIT 1"));
        if (row == null || row.getStatus() == null || row.getStatus() != 1) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (row.getExpireTime() != null && row.getExpireTime().isBefore(now)) {
            log.info("API 密钥已过期 tenant={} key={}", row.getTenantCode(), row.getKeyPrefix());
            return null;
        }
        ApiKey touch = new ApiKey();
        touch.setId(row.getId());
        touch.setCallCount((row.getCallCount() == null ? 0L : row.getCallCount()) + 1);
        touch.setLastUsedAt(now);
        apiKeyMapper.updateById(touch);
        return row.getTenantCode();
    }

    // ------------------------------------------------------------------ 内部

    private static String normalizeWhitelist(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String piece : raw.split("[,，\\s]+")) {
            String ip = piece.trim();
            if (!ip.isEmpty() && !parts.contains(ip)) {
                parts.add(ip);
            }
        }
        return String.join(",", parts);
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(48);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BizException(50001, "密钥计算失败：" + e.getMessage());
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int intValue(Short value, int fallback) {
        return value == null ? fallback : value;
    }

    private static String text(LocalDateTime time) {
        return time == null ? null : time.format(TIME_FORMAT);
    }

    private static LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim().replace('T', ' ');
        try {
            return LocalDateTime.parse(text.length() > 19 ? text.substring(0, 19) : text, TIME_FORMAT);
        } catch (Exception e) {
            log.warn("密钥过期时间解析失败，已忽略：{}", value);
            return null;
        }
    }
}
