package io.supportflow.user.service;

import io.supportflow.common.api.PageResult;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.common.audit.AuditLogClient;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.util.Texts;
import io.supportflow.user.dto.AssignableRoleVO;
import io.supportflow.user.dto.CreateBody;
import io.supportflow.user.dto.CreatedAccountVO;
import io.supportflow.user.dto.PlatformUserRow;
import io.supportflow.user.dto.UpdateBody;
import io.supportflow.user.entity.SysRole;
import io.supportflow.user.entity.SysUser;
import io.supportflow.user.entity.SysUserRole;
import io.supportflow.user.mapper.SysRoleMapper;
import io.supportflow.user.mapper.SysUserMapper;
import io.supportflow.user.mapper.SysUserRoleMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 平台账号中心（原型「用户管理」）：平台管理员与企业用户统一管理。
 *
 * <p>三件事：列表（跨租户、带角色与状态）、增改（含角色分配）、禁用启用与重置密码。
 * 所有入口都要求调用方是**平台账号**（见 controller 的 requirePlatform）——
 * 这是平台运营域，企业账号一律进不来。</p>
 */
@Service
public class PlatformAccountService {

    private static final Logger log = LoggerFactory.getLogger(PlatformAccountService.class);

    /** 平台账号固定挂在这个“租户”下 */
    public static final String PLATFORM_TENANT = "PLATFORM";

    /** 新建账号时的初始密码（生产应改成邮件邀请 / 一次性链接，这里先给运营一个可转告的默认值） */
    private static final String DEFAULT_PASSWORD = "SupportFlow@123456";

    private static final int USER_TYPE_PLATFORM = 1;
    private static final int USER_TYPE_ENTERPRISE = 2;
    private static final int STATUS_NORMAL = 1;

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SnowflakeIdGenerator idGenerator;
    /** 审计日志：平台账号中心的写操作都要留痕（谁、对谁、做了什么、结果） */
    private final AuditLogClient auditLogClient;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public PlatformAccountService(SysUserMapper userMapper,
                                  SysRoleMapper roleMapper,
                                  SysUserRoleMapper userRoleMapper,
                                  SnowflakeIdGenerator idGenerator,
                                  AuditLogClient auditLogClient) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.idGenerator = idGenerator;
        this.auditLogClient = auditLogClient;
    }

    /** 审计用的模块名（与平台页筛选器的选项保持一致） */
    private static final String MODULE = "用户管理";

    /** 账号列表（跨租户；支持类型 / 状态 / 租户 / 关键字筛选） */
    public PageResult<PlatformUserRow> page(Integer userType, Integer status, String tenantCode,
                                            String keyword, int page, int pageSize) {
        String tenant = Texts.blankToNull(tenantCode);
        String kw = Texts.blankToNull(keyword);
        long total = userMapper.countPlatformUsers(userType, status, tenant, kw);
        List<Map<String, Object>> rows = userMapper.selectPlatformUsers(
                userType, status, tenant, kw, pageSize, (long) (page - 1) * pageSize);
        List<PlatformUserRow> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            list.add(toPlatformUserRow(row));
        }
        return new PageResult<>(total, page, pageSize, list);
    }

    /**
     * 平台账号行：SQL 返回的是下划线列名的 Map，这里显式转成实体。
     *
     * <p>用 {@code row.get("user_no")} 这种下划线键是**故意**的：MyBatis 的
     * {@code resultType="map"} 不会走驼峰命名策略，键就是列名本身。</p>
     */
    private static PlatformUserRow toPlatformUserRow(Map<String, Object> row) {
        return new PlatformUserRow(longOf(row.get("user_id")), stringOf(row.get("user_no")),
                stringOf(row.get("name")), stringOf(row.get("phone")), stringOf(row.get("email")),
                intOf(row.get("user_type")), stringOf(row.get("tenant_code")),
                intOf(row.get("status")), localDateTimeOf(row.get("last_login_time")),
                localDateTimeOf(row.get("create_time")), stringOf(row.get("role_ids")),
                stringOf(row.get("role_codes")), stringOf(row.get("role_names")));
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longOf(Object value) {
        return value instanceof Number number ? number.longValue()
                : value == null ? null : Long.valueOf(String.valueOf(value).trim());
    }

    private static Integer intOf(Object value) {
        return value instanceof Number number ? number.intValue()
                : value == null ? null : Integer.valueOf(String.valueOf(value).trim());
    }

    private static LocalDateTime localDateTimeOf(Object value) {
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

    /**
     * 新建账号。
     *
     * <p>平台账号固定挂在 PLATFORM 下；企业账号必须指定租户编码——
     * 不然会出现“企业账号却不属于任何企业”这种查不出来源的账号。</p>
     */
    @Transactional
    public CreatedAccountVO create(LoginUser operator, CreateBody body) {
        int userType = body.userType() == null ? USER_TYPE_ENTERPRISE : body.userType();
        if (userType != USER_TYPE_PLATFORM && userType != USER_TYPE_ENTERPRISE) {
            throw new BizException(40001, "账号类型只能是 1（平台账号）或 2（企业账号）");
        }
        String tenantCode = userType == USER_TYPE_PLATFORM
                ? PLATFORM_TENANT : Texts.blankToNull(body.tenantCode());
        if (tenantCode == null) {
            throw new BizException(40001, "企业账号必须指定所属租户编码");
        }
        String name = require(body.name(), "姓名");
        String phone = Texts.blankToNull(body.phone());
        String email = Texts.blankToNull(body.email());
        if (phone == null && email == null) {
            throw new BizException(40001, "手机号与邮箱至少填一个（登录用）");
        }
        if (userMapper.countByPhoneOrEmail(phone, email) > 0) {
            throw new BizException(40001, "手机号或邮箱已被占用");
        }

        long id = idGenerator.nextId();
        String initialPassword = Texts.blankToNull(body.password()) == null ? DEFAULT_PASSWORD : body.password().trim();
        LocalDateTime now = LocalDateTime.now();
        userMapper.insert(SysUser.builder()
                .id(id)
                .tenantCode(tenantCode)
                .userNo("U" + id)
                .name(name)
                .phone(phone)
                .email(email)
                .password(passwordEncoder.encode(initialPassword))
                .userType(userType)
                .status(STATUS_NORMAL)
                .createTime(now)
                .updateTime(now)
                .creator("PLATFORM_ADMIN")
                .deleted(false)
                .build());
        assignRoles(id, tenantCode, body.roleIds());
        log.info("平台账号中心新建账号 id={} userType={} tenant={} roleIds={}",
                id, userType, tenantCode, body.roleIds());
        auditLogClient.record(operator, MODULE, "新建账号", "账号", "U" + id, true,
                "新建" + (userType == USER_TYPE_PLATFORM ? "平台账号" : "企业账号")
                        + "：" + name + "（租户 " + tenantCode + "）");

        // 初始密码只在这里回显一次：运营转告本人后应当立即要求修改
        return new CreatedAccountVO(id, "U" + id, initialPassword);
    }

    /** 改资料（不动角色，角色有单独接口） */
    @Transactional
    public void update(LoginUser operator, long userId, UpdateBody body) {
        SysUser user = requireUser(userId);
        String phone = Texts.blankToNull(body.phone());
        String email = Texts.blankToNull(body.email());
        if (phone != null || email != null) {
            int taken = userMapper.countByPhoneOrEmail(phone, email);
            boolean sameAsSelf = (phone == null || phone.equals(user.getPhone()))
                    && (email == null || email.equals(user.getEmail()));
            if (taken > 0 && !sameAsSelf) {
                throw new BizException(40001, "手机号或邮箱已被其他账号占用");
            }
        }
        SysUser update = new SysUser();
        update.setId(userId);
        update.setName(require(body.name(), "姓名"));
        update.setPhone(phone);
        update.setEmail(email);
        update.setUpdateTime(LocalDateTime.now());
        update.setEditor("PLATFORM_ADMIN");
        userMapper.updateById(update);
        auditLogClient.record(operator, MODULE, "编辑账号", "账号", user.getUserNo(), true,
                "修改账号资料：" + update.getName());
    }

    /** 启用 / 停用：停用后无法登录（登录时会校验 status） */
    @Transactional
    public void updateStatus(LoginUser operator, long userId, int status) {
        if (status != 1 && status != 2) {
            throw new BizException(40001, "状态只能是 1（正常）或 2（停用）");
        }
        SysUser user = requireUser(userId);
        if (user.getUserType() != null && user.getUserType() == USER_TYPE_PLATFORM
                && user.getTenantCode() != null && PLATFORM_TENANT.equals(user.getTenantCode())
                && status == 2 && isLastActivePlatformAdmin(userId)) {
            // 防呆：别把最后一个还能登录的平台管理员停掉，否则谁都进不来
            throw new BizException(40001, "这是最后一个可用的平台管理员账号，不能停用");
        }
        SysUser update = new SysUser();
        update.setId(userId);
        update.setStatus(status);
        update.setUpdateTime(LocalDateTime.now());
        update.setEditor("PLATFORM_ADMIN");
        userMapper.updateById(update);
        log.info("平台账号中心{}账号 id={}", status == 1 ? "启用" : "停用", userId);
        // 启停是高危动作（一停就登不进来），审计里必须留一条
        auditLogClient.record(operator, MODULE, status == 1 ? "启用账号" : "停用账号",
                "账号", user.getUserNo(), true,
                (status == 1 ? "恢复登录权限：" : "禁止登录：") + user.getName());
    }

    /** 重置密码 */
    @Transactional
    public void resetPassword(LoginUser operator, long userId, String newPassword) {
        SysUser user = requireUser(userId);
        String password = require(newPassword, "新密码");
        if (password.length() < 6) {
            throw new BizException(40001, "密码至少 6 位");
        }
        SysUser update = new SysUser();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(password));
        update.setUpdateTime(LocalDateTime.now());
        update.setEditor("PLATFORM_ADMIN");
        userMapper.updateById(update);
        log.info("平台账号中心重置密码 id={}", userId);
        // 只记"重置了谁的密码"，不记密码本身
        auditLogClient.record(operator, MODULE, "重置密码", "账号", user.getUserNo(), true,
                "重置登录密码：" + user.getName());
    }

    /** 给账号分配角色（全量覆盖）：平台账号给平台角色，企业账号给该租户的企业角色 */
    @Transactional
    public void setRoles(LoginUser operator, long userId, List<Long> roleIds) {
        SysUser user = requireUser(userId);
        assignRoles(userId, user.getTenantCode(), roleIds);
        auditLogClient.record(operator, MODULE, "分配角色", "账号", user.getUserNo(), true,
                "把角色改为：" + (roleIds == null || roleIds.isEmpty()
                        ? "（无角色）" : roleNames(roleIds)));
    }

    /** 角色 id 拼成名字，审计里看得懂 */
    private String roleNames(List<Long> roleIds) {
        List<String> names = new ArrayList<>();
        for (Long roleId : roleIds) {
            SysRole role = roleMapper.selectById(roleId);
            names.add(role == null ? String.valueOf(roleId) : role.getRoleName());
        }
        return String.join("、", names);
    }

    /** 可分配的角色（按账号类型给：平台角色 / 企业角色） */
    public List<AssignableRoleVO> assignableRoles(String tenantCode) {
        String tenant = Texts.blankToNull(tenantCode) == null ? PLATFORM_TENANT : tenantCode;
        List<SysRole> roles = roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getTenantCode, tenant)
                .eq(SysRole::getDeleted, false)
                .eq(SysRole::getIsEnabled, true)
                .orderByAsc(SysRole::getRoleType)
                .orderByAsc(SysRole::getId));
        List<AssignableRoleVO> list = new ArrayList<>();
        for (SysRole role : roles) {
            list.add(new AssignableRoleVO(String.valueOf(role.getId()), role.getRoleCode(),
                    role.getRoleName(), role.getRoleType()));
        }
        return list;
    }

    /** 导出 CSV（带 UTF-8 BOM，Excel 打开不乱码） */
    public String exportCsv(Integer userType, Integer status, String tenantCode, String keyword) {
        List<Map<String, Object>> rows = userMapper.selectPlatformUsersForExport(
                userType, status, Texts.blankToNull(tenantCode), Texts.blankToNull(keyword));
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("用户编号,姓名,手机号,邮箱,账号类型,所属租户,角色,状态,最近登录,创建时间\n");
        for (Map<String, Object> row : rows) {
            sb.append(Texts.csv(row.get("user_no"))).append(',')
                    .append(Texts.csv(row.get("name"))).append(',')
                    .append(Texts.csv(row.get("phone"))).append(',')
                    .append(Texts.csv(row.get("email"))).append(',')
                    .append(Texts.csv(userTypeText(row.get("user_type")))).append(',')
                    .append(Texts.csv(row.get("tenant_code"))).append(',')
                    .append(Texts.csv(row.get("role_names"))).append(',')
                    .append(Texts.csv(statusText(row.get("status")))).append(',')
                    .append(Texts.csv(row.get("last_login_time"))).append(',')
                    .append(Texts.csv(row.get("create_time"))).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 内部

    private void assignRoles(long userId, String tenantCode, List<Long> roleIds) {
        // 全量覆盖：先解绑旧角色，再按提交的集合绑定（未提交/空 = 清空角色）
        userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getUserId, userId)
                .eq(SysUserRole::getTenantCode, tenantCode));
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        for (Long roleId : roleIds) {
            SysRole role = roleMapper.selectById(roleId);
            if (role == null || Boolean.TRUE.equals(role.getDeleted())) {
                throw new BizException(40001, "角色不存在：" + roleId);
            }
            if (!tenantCode.equals(role.getTenantCode())) {
                throw new BizException(40001,
                        "角色「" + role.getRoleName() + "」不属于这个账号的归属（" + tenantCode + "）");
            }
            userRoleMapper.insertIgnore(SysUserRole.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenantCode)
                    .userId(userId)
                    .roleId(roleId)
                    .createTime(LocalDateTime.now())
                    .build());
        }
    }

    /** 平台账号里是否只剩这一个可用的（防止把最后一个管理员停掉） */
    private boolean isLastActivePlatformAdmin(long excludeUserId) {
        Long others = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantCode, PLATFORM_TENANT)
                .eq(SysUser::getUserType, USER_TYPE_PLATFORM)
                .eq(SysUser::getStatus, STATUS_NORMAL)
                .eq(SysUser::getDeleted, false)
                .ne(SysUser::getId, excludeUserId));
        return others == null || others == 0;
    }

    private SysUser requireUser(long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || Boolean.TRUE.equals(user.getDeleted())) {
            throw new BizException(40401, "账号不存在：" + userId);
        }
        return user;
    }

    private static String require(String value, String label) {
        String text = Texts.blankToNull(value);
        if (text == null) {
            throw new BizException(40001, label + "不能为空");
        }
        return text;
    }

    private static String userTypeText(Object value) {
        if (value == null) {
            return "";
        }
        int type = ((Number) value).intValue();
        return type == USER_TYPE_PLATFORM ? "平台账号" : "企业账号";
    }

    private static String statusText(Object value) {
        if (value == null) {
            return "";
        }
        return ((Number) value).intValue() == STATUS_NORMAL ? "正常" : "停用";
    }
}
