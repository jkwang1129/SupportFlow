package io.supportflow.user.service;

import io.supportflow.common.api.ResultCode;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.common.util.Texts;
import io.supportflow.user.constant.AuthConstants;
import io.supportflow.user.dto.Challenge;
import io.supportflow.user.dto.DraftRequest;
import io.supportflow.user.dto.DraftResponse;
import io.supportflow.user.dto.Identity;
import io.supportflow.user.dto.LoginRequest;
import io.supportflow.user.dto.LoginResponse;
import io.supportflow.user.dto.MeResponse;
import io.supportflow.user.dto.RegisterCodeResult;
import io.supportflow.user.dto.RegisterPolicyVO;
import io.supportflow.user.dto.RegisterRequest;
import io.supportflow.user.dto.RegisterResponse;
import io.supportflow.user.entity.LoginLog;
import io.supportflow.user.entity.SysUser;
import io.supportflow.user.enums.LoginResult;
import io.supportflow.user.enums.LoginType;
import io.supportflow.user.enums.MemberRole;
import io.supportflow.user.enums.UserStatus;
import io.supportflow.user.enums.UserType;
import io.supportflow.user.internal.TenantEnterpriseDraftClient;
import io.supportflow.user.mapper.LoginLogMapper;
import io.supportflow.user.mapper.SysUserMapper;
import io.supportflow.user.security.CaptchaStore;
import io.supportflow.user.security.LoginGuard;
import io.supportflow.user.security.RegistrationPolicyClient;
import io.supportflow.user.security.JwtService;
import io.supportflow.user.security.RegisterCodeStore;
import io.supportflow.user.security.TwoFactorStore;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 企业用户注册 / 登录业务。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final DateTimeFormatter USER_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SysUserMapper sysUserMapper;
    private final LoginLogMapper loginLogMapper;
    private final TenantEnterpriseDraftClient tenantDraftClient;
    private final CaptchaStore captchaStore;
    /** 登录防线：IP 白名单 + 连续失败锁定（阈值来自平台「安全中心」） */
    private final LoginGuard loginGuard;
    /** 注册审核策略（开放注册开关 / 白名单 / 试用天数） */
    private final RegistrationPolicyClient registrationPolicyClient;
    /** 双因素（登录二次验证）验证码存储 */
    private final TwoFactorStore twoFactorStore;
    /** 注册邮箱验证码存储（「注册需手机 / 邮箱双重验证」的执行点） */
    private final RegisterCodeStore registerCodeStore;
    /** 登录验证码邮件（与重置密码同一套 SMTP） */
    private final MemberMailService memberMailService;
    /** 角色查询：判断"这个账号是不是企业管理员"（双因素覆盖范围用） */
    private final PlatformRoleService platformRoleService;
    private final JwtService jwtService;
    private final SnowflakeIdGenerator idGenerator;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 双因素覆盖范围：2-全部账号（含坐席）；1-仅平台账号与企业管理员 */
    private static final int TWO_FACTOR_SCOPE_ALL = 2;

    /** 本地联调：未配 SMTP 时把登录验证码带回响应（默认关，生产必须关） */
    private final boolean twoFactorCodeDebug;

    /** 本地联调：未配 SMTP 时把注册验证码带回响应（默认关，生产必须关） */
    private final boolean registerCodeDebug;

    public AuthService(
            SysUserMapper sysUserMapper,
            LoginLogMapper loginLogMapper,
            TenantEnterpriseDraftClient tenantDraftClient,
            CaptchaStore captchaStore,
            LoginGuard loginGuard,
            RegistrationPolicyClient registrationPolicyClient,
            TwoFactorStore twoFactorStore,
            RegisterCodeStore registerCodeStore,
            MemberMailService memberMailService,
            PlatformRoleService platformRoleService,
            JwtService jwtService,
            SnowflakeIdGenerator idGenerator,
            @Value(
                    "${supportflow.auth.two-factor-code-debug:false}") boolean twoFactorCodeDebug,
            @Value(
                    "${supportflow.auth.register-code-debug:false}") boolean registerCodeDebug
    ) {
        this.sysUserMapper = sysUserMapper;
        this.loginLogMapper = loginLogMapper;
        this.tenantDraftClient = tenantDraftClient;
        this.captchaStore = captchaStore;
        this.loginGuard = loginGuard;
        this.registrationPolicyClient = registrationPolicyClient;
        this.twoFactorStore = twoFactorStore;
        this.registerCodeStore = registerCodeStore;
        this.memberMailService = memberMailService;
        this.platformRoleService = platformRoleService;
        this.jwtService = jwtService;
        this.idGenerator = idGenerator;
        this.twoFactorCodeDebug = twoFactorCodeDebug;
        this.registerCodeDebug = registerCodeDebug;
    }

    /**
     * 企业账号注册：先建账号（企业类型、状态正常；审核开通前挂平台租户 PLATFORM），
     * 再落企业草稿；企业草稿失败时补偿回收账号。
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (!captchaStore.verify(request.captchaId(), request.captchaCode())) {
            throw new BizException(40001, "图形验证码错误或已过期");
        }
        // 注册审核策略的执行点：平台关掉"开放企业注册"之后，注册必须进不来
        if (!registrationPolicyClient.allowRegister()) {
            throw new BizException(40301, "平台当前未开放企业注册，请联系平台运营");
        }
        // 注册审核策略的执行点②：注册需手机 / 邮箱双重验证
        // 策略开着就必须带对邮箱验证码——验证码是发到"注册时填的这个邮箱"的，
        // 顺带把"邮箱真实可达"这件事也验了（以前随便填个邮箱就能注册）。
        if (registrationPolicyClient.requireDoubleVerify()) {
            String verifyCode = request.verifyCode();
            if (verifyCode == null || verifyCode.isBlank()) {
                throw new BizException(40001, "请输入邮箱验证码");
            }
            if (!registerCodeStore.verify(request.email(), verifyCode)) {
                throw new BizException(40002, "邮箱验证码错误或已过期");
            }
        }
        String phone = request.phone().trim();
        String email = request.email().trim().toLowerCase();
        if (sysUserMapper.countByPhoneOrEmail(phone, email) > 0) {
            throw new BizException(40002, "该手机号或邮箱已注册，请直接登录");
        }

        long userId = idGenerator.nextId();
        String userNo = generateUserNo();
        SysUser user = SysUser.builder()
                .id(userId)
                .tenantCode(AuthConstants.TENANT_CODE_PLATFORM)
                .userNo(userNo)
                .name(request.contactName().trim())
                .phone(phone)
                .email(email)
                .password(passwordEncoder.encode(request.password()))
                .userType(UserType.ENTERPRISE.getCode())
                .status(UserStatus.NORMAL.getCode())
                .creator("SELF_REGISTER")
                .deleted(false)
                .build();
        sysUserMapper.insert(user);

        try {
            DraftResponse draft = tenantDraftClient.createDraft(
                    new DraftRequest(
                            userId,
                            request.companyName().trim(),
                            Texts.blankToNull(request.industry()),
                            Texts.blankToNull(request.scale()),
                            user.getName(),
                            phone,
                            email
                    )
            );
            return new RegisterResponse(
                    userId,
                    userNo,
                    user.getName(),
                    phone,
                    email,
                    draft.enterpriseId(),
                    draft.enterpriseCode(),
                    draft.status(),
                    AuthConstants.TENANT_CODE_PLATFORM,
                    UserType.ENTERPRISE.getCode()
            );
        } catch (Exception e) {
            // 补偿：回收已创建但企业草稿未落库的账号
            softDeleteById(userId);
            log.error("企业草稿创建失败，已回收注册账号 userId={}, userNo={}", userId, userNo, e);
            throw e;
        }
    }

    /**
     * 账号密码登录（支持手机号 / 邮箱 / 用户编号），记录登录日志并签发 JWT。
     */
    public LoginResponse login(LoginRequest request, String ip, String userAgent) {
        if (!captchaStore.verify(request.captchaId(), request.captchaCode())) {
            throw new BizException(40001, "图形验证码错误或已过期");
        }
        // 安全策略的执行点①：管理端登录 IP 白名单（策略里没开就一律放行）
        if (!loginGuard.ipAllowed(ip)) {
            throw new BizException(40301, "当前 IP 不在管理端登录白名单内，请联系平台运营");
        }
        // 安全策略的执行点②：连续失败锁定（阈值与时长来自平台「安全中心」）
        int lockedMinutes = loginGuard.lockedMinutes(request.account());
        if (lockedMinutes > 0) {
            throw new BizException(40301, "该账号连续登录失败已锁定，请 " + lockedMinutes + " 分钟后再试");
        }

        LocalDateTime now = LocalDateTime.now();
        SysUser user = sysUserMapper.findByAccount(request.account().trim());
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            loginGuard.recordFailure(request.account());
            insertLoginLog(
                    user == null ? AuthConstants.TENANT_CODE_PLATFORM : user.getTenantCode(),
                    user == null ? 0L : user.getId(),
                    ip,
                    userAgent,
                    LoginResult.FAIL.getCode(),
                    now
            );
            throw new BizException(40002, "账号或密码错误");
        }
        if (user.getStatus() != UserStatus.NORMAL.getCode()) {
            throw new BizException(40301, "账号已被停用或锁定，请联系平台运营");
        }

        // 安全策略的执行点③：双因素（登录二次验证）
        // 密码对了**也不算登录成功**——先发验证码，第二步校验通过才发 token。
        // 这里不写登录日志：登录日志记的是"最终结果"，成功与失败都在第二步记。
        if (needTwoFactor(user)) {
            return issueTwoFactorChallenge(user, request.account(), ip, userAgent);
        }

        loginGuard.recordSuccess(request.account());
        updateLastLoginTime(user.getId(), now);
        insertLoginLog(user.getTenantCode(), user.getId(), ip, userAgent,
                LoginResult.SUCCESS.getCode(), now);
        return tokenResponse(user);
    }

    /**
     * 登录第二步：校验邮箱验证码并签发 token。
     *
     * <p>挑战号是"密码已经验证通过"的凭据，只认它；验证码错一次记一次失败
     * （和密码错误共用锁定计数，避免有人拿正确密码来无限试验证码）。</p>
     */
    public LoginResponse loginWithTwoFactor(String challengeId, String code, String ip, String userAgent) {
        // 先取身份（挑战过期会直接抛"请重新登录"），再校验码——码错了也要知道是谁在试
        Identity identity = twoFactorStore.peek(challengeId);
        if (!twoFactorStore.verify(challengeId, code)) {
            loginGuard.recordFailure(identity.account());
            insertLoginLog(identity.tenantCode(), identity.userId(), ip, userAgent,
                    LoginResult.FAIL.getCode(), LocalDateTime.now());
            throw new BizException(40002, "验证码错误");
        }
        SysUser user = sysUserMapper.selectById(identity.userId());
        if (user == null || Boolean.TRUE.equals(user.getDeleted())) {
            throw new BizException(40401, "账号不存在，请重新登录");
        }
        if (user.getStatus() != UserStatus.NORMAL.getCode()) {
            throw new BizException(40301, "账号已被停用或锁定，请联系平台运营");
        }

        LocalDateTime now = LocalDateTime.now();
        loginGuard.recordSuccess(identity.account());
        updateLastLoginTime(user.getId(), now);
        insertLoginLog(user.getTenantCode(), user.getId(), ip, userAgent,
                LoginResult.SUCCESS.getCode(), now);
        log.info("双因素登录成功 userId={} userNo={}", user.getId(), user.getUserNo());
        return tokenResponse(user);
    }

    /** 登录第二步：重发验证码（挑战号不变，60 秒内不能重复发） */
    public LoginResponse resendTwoFactorCode(String challengeId) {
        Identity identity = twoFactorStore.peek(challengeId);
        SysUser user = sysUserMapper.selectById(identity.userId());
        if (user == null || Boolean.TRUE.equals(user.getDeleted())) {
            throw new BizException(40401, "账号不存在，请重新登录");
        }
        String code = twoFactorStore.resend(challengeId);
        boolean sent = sendLoginCode(user, code);
        if (!sent && !twoFactorCodeDebug) {
            throw new BizException(50002, SEND_FAIL_MESSAGE);
        }
        return LoginResponse.challenge(user.getId(), user.getUserNo(), user.getName(),
                user.getUserType(), user.getTenantCode(), challengeId,
                Texts.maskEmail(user.getEmail()), TwoFactorStore.EXPIRE_SECONDS,
                twoFactorCodeDebug && !sent ? code : null);
    }

    /** 发不出验证码时的统一说法（把逃生路径写清楚，别让人对着报错干瞪眼） */
    private static final String SEND_FAIL_MESSAGE =
            "登录验证码发送失败，请稍后重试；若邮件通道未配置，请联系平台运营在「安全中心 → 安全策略」"
                    + "（或 ops_db.security_policy）关闭「强制双因素认证」";

    /** 注册验证码发不出去时的说法（逃生路径同样写清楚） */
    private static final String REGISTER_SEND_FAIL_MESSAGE =
            "注册验证码发送失败，请稍后重试；若邮件通道未配置，请联系平台运营在「平台设置 → 注册与审核」"
                    + "关闭「注册需手机 / 邮箱双重验证」";

    /**
     * 发送注册邮箱验证码（注册页的「获取验证码」）。
     *
     * <p>也要先过图形验证码：否则这个接口就成了"给任意邮箱发信的免费工具"。</p>
     */
    public RegisterCodeResult sendRegisterCode(String email, String captchaId, String captchaCode) {
        if (!captchaStore.verify(captchaId, captchaCode)) {
            throw new BizException(40001, "图形验证码错误或已过期");
        }
        if (!registrationPolicyClient.allowRegister()) {
            throw new BizException(40301, "平台当前未开放企业注册，请联系平台运营");
        }
        if (!registrationPolicyClient.requireDoubleVerify()) {
            throw new BizException(40001, "平台未开启注册双重验证，无需邮箱验证码");
        }
        String target = email == null ? "" : email.trim().toLowerCase();
        if (target.isBlank()) {
            throw new BizException(40001, "请先填写邮箱");
        }
        if (sysUserMapper.countByPhoneOrEmail("", target) > 0) {
            throw new BizException(40002, "该邮箱已注册，请直接登录");
        }
        String code = registerCodeStore.issue(target);
        boolean sent = sendRegisterCodeMail(target, code);
        if (!sent && !registerCodeDebug) {
            throw new BizException(50002, REGISTER_SEND_FAIL_MESSAGE);
        }
        log.info("注册验证码已生成 email={} sent={}", Texts.maskEmail(target), sent);
        return new RegisterCodeResult(Texts.maskEmail(target), RegisterCodeStore.EXPIRE_SECONDS,
                registerCodeDebug && !sent ? code : null);
    }

    /** 发注册验证码；抛异常按"没发出去"处理 */
    private boolean sendRegisterCodeMail(String email, String code) {
        try {
            return memberMailService.sendRegisterCodeEmail(email, code);
        } catch (Exception e) {
            log.warn("注册验证码发送失败 email={} error={}", Texts.maskEmail(email), e.getMessage());
            return false;
        }
    }

    /** 注册策略（注册页用来决定要不要显示"邮箱验证码"这一栏） */
    public RegisterPolicyVO registerPolicyView() {
        return new RegisterPolicyVO(registrationPolicyClient.allowRegister(),
                registrationPolicyClient.requireDoubleVerify());
    }

    /** 开一个二次验证挑战并把验证码发到账号邮箱 */
    private LoginResponse issueTwoFactorChallenge(SysUser user, String loginAccount,
                                                  String ip, String userAgent) {
        Challenge challenge = twoFactorStore.issue(
                user.getId(), loginAccount, user.getTenantCode());
        boolean sent = sendLoginCode(user, challenge.code());
        if (!sent && !twoFactorCodeDebug) {
            throw new BizException(50002, SEND_FAIL_MESSAGE);
        }
        log.info("登录需要二次验证 userId={} userNo={} email={} ip={} sent={}",
                user.getId(), user.getUserNo(), Texts.maskEmail(user.getEmail()), ip, sent);
        // 不写登录日志：第一步还没出结果，日志里一行 = 一次登录的最终结果（第二步才写）
        return LoginResponse.challenge(user.getId(), user.getUserNo(), user.getName(),
                user.getUserType(), user.getTenantCode(), challenge.challengeId(),
                Texts.maskEmail(user.getEmail()), TwoFactorStore.EXPIRE_SECONDS,
                twoFactorCodeDebug && !sent ? challenge.code() : null);
    }

    /** 发验证码；抛异常按"没发出去"处理（调用方决定放不放行） */
    private boolean sendLoginCode(SysUser user, String code) {
        String email = user.getEmail();
        if (email == null || email.isBlank()) {
            log.warn("账号未绑定邮箱，无法发送登录验证码 userId={}", user.getId());
            return false;
        }
        try {
            return memberMailService.sendLoginCodeEmail(email, code);
        } catch (Exception e) {
            log.warn("登录验证码发送失败 userId={} email={} error={}",
                    user.getId(), Texts.maskEmail(email), e.getMessage());
            return false;
        }
    }

    /**
     * 这次登录要不要走二次验证。**判定逻辑只写在这一处**：
     *
     * <ul>
     *   <li>策略开关关着 → 不用（一步登录）；</li>
     *   <li>平台账号（user_type=1）→ 一律要（平台侧能看全平台数据，风险最高）；</li>
     *   <li>企业账号 → 范围 2（全部账号）要；范围 1（默认）只有**企业管理员**（ADMIN 角色）要。</li>
     * </ul>
     *
     * <p>为什么按角色判而不是维护一张名单：企业管理员的身份就是 ADMIN 角色，
     * 换人、加人都会自动跟着变——名单一定会忘记更新。</p>
     */
    private boolean needTwoFactor(SysUser user) {
        if (!loginGuard.twoFactorRequired()) {
            return false;
        }
        if (user.getUserType() != null && user.getUserType() == UserType.PLATFORM.getCode()) {
            return true;
        }
        if (loginGuard.twoFactorScope() >= TWO_FACTOR_SCOPE_ALL) {
            return true;
        }
        List<String> roles = platformRoleService.roleCodesOf(user.getTenantCode(), user.getId());
        return roles.contains(MemberRole.ADMIN.getCode());
    }

    /** 签名 token 并组装登录响应（一步登录与两步登录共用） */
    private LoginResponse tokenResponse(SysUser user) {
        String token = jwtService.createToken(new LoginUser(
                user.getId(),
                user.getUserNo(),
                user.getName(),
                user.getUserType(),
                user.getTenantCode()
        ));
        return new LoginResponse(token, user.getId(), user.getUserNo(), user.getName(),
                user.getUserType(), user.getTenantCode(), user.getAvatar(),
                null, null, null, null, null);
    }

    /**
     * 修改当前登录用户密码（需校验原密码）。
     */
    public void changePassword(LoginUser loginUser, String oldPassword, String newPassword) {
        SysUser user = findById(loginUser.userId())
                .orElseThrow(() -> new BizException(ResultCode.UNAUTHORIZED));
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BizException(40002, "原密码不正确");
        }
        if (oldPassword.equals(newPassword)) {
            throw new BizException(40002, "新密码不能与原密码相同");
        }
        LambdaUpdateWrapper<SysUser> update = Wrappers.lambdaUpdate(SysUser.class)
                .eq(SysUser::getId, user.getId())
                .eq(SysUser::getDeleted, false)
                .set(SysUser::getPassword, passwordEncoder.encode(newPassword))
                .set(SysUser::getUpdateTime, LocalDateTime.now());
        sysUserMapper.update(null, update);
        log.info("用户修改密码成功 userId={}, userNo={}", user.getId(), user.getUserNo());
    }

    /**
     * 当前登录用户（前端进后台后靠它恢复会话）。
     *
     * <p>先确认账号仍在库里（被删就当作未登录），再算角色与权限：
     * 权限只对平台账号下发，企业侧仍按 {@code userType} + 企业角色判断，
     * 避免这一改动影响企业端现有菜单逻辑。</p>
     */
    public MeResponse me(LoginUser loginUser) {
        if (sysUserMapper.findByAccount(loginUser.userNo()) == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        String tenant = loginUser.tenantCode() == null ? "" : loginUser.tenantCode();
        List<String> roles = platformRoleService.roleCodesOf(tenant, loginUser.userId());
        List<String> permissions = loginUser.userType() == 1
                ? platformRoleService.permissionsOf(loginUser.userId())
                : List.of();
        return new MeResponse(loginUser.userId(), loginUser.userNo(), loginUser.name(),
                loginUser.userType(), tenant, roles, permissions);
    }

    /**
     * 刷新访问令牌：以数据库中的最新租户状态重新签发（企业审核通过后无需重新登录）。
     */
    public LoginResponse refreshToken(LoginUser loginUser) {
        SysUser user = findById(loginUser.userId())
                .orElseThrow(() -> new BizException(ResultCode.UNAUTHORIZED));
        String token = jwtService.createToken(new LoginUser(
                user.getId(),
                user.getUserNo(),
                user.getName(),
                user.getUserType(),
                user.getTenantCode()
        ));
        return new LoginResponse(
                token,
                user.getId(),
                user.getUserNo(),
                user.getName(),
                user.getUserType(),
                user.getTenantCode(),
                user.getAvatar(),
                null, null, null, null, null
        );
    }

        /**
     * 按用户 ID 查询（未删除）。
     */
    public Optional<SysUser> findById(long userId) {
        return Optional.ofNullable(sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getId, userId)
                        .eq(SysUser::getDeleted, false)
                        .last("LIMIT 1")
        ));
    }

    private void insertLoginLog(
            String tenantCode,
            long userId,
            String ip,
            String device,
            int result,
            LocalDateTime loginTime
    ) {
        String safeDevice = device == null || device.isBlank()
                ? null
                : device.substring(0, Math.min(device.length(), 128));
        loginLogMapper.insert(LoginLog.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenantCode)
                .userId(userId)
                .loginType(LoginType.PASSWORD.getCode())
                .ip(ip)
                .device(safeDevice)
                .result(result)
                .loginTime(loginTime)
                .build());
    }

    private void updateLastLoginTime(long userId, LocalDateTime loginTime) {
        LambdaUpdateWrapper<SysUser> update = Wrappers.lambdaUpdate(SysUser.class)
                .eq(SysUser::getId, userId)
                .eq(SysUser::getDeleted, false)
                .set(SysUser::getLastLoginTime, loginTime)
                .set(SysUser::getUpdateTime, LocalDateTime.now());
        sysUserMapper.update(null, update);
    }

    private void softDeleteById(long userId) {
        LambdaUpdateWrapper<SysUser> update = Wrappers.lambdaUpdate(SysUser.class)
                .eq(SysUser::getId, userId)
                .set(SysUser::getDeleted, true)
                .set(SysUser::getUpdateTime, LocalDateTime.now());
        sysUserMapper.update(null, update);
    }

    private String generateUserNo() {
        return "U" + USER_NO_FORMAT.format(LocalDateTime.now())
                + ThreadLocalRandom.current().nextInt(100, 1000);
    }

}
