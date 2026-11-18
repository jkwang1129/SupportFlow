package io.supportflow.user.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.web.RequestUtils;
import io.supportflow.user.dto.CaptchaResponse;
import io.supportflow.user.dto.ChangePasswordRequest;
import io.supportflow.user.dto.LoginRequest;
import io.supportflow.user.dto.LoginResponse;
import io.supportflow.user.dto.MeResponse;
import io.supportflow.user.dto.RegisterCodeResponse;
import io.supportflow.user.dto.RegisterCodeResult;
import io.supportflow.user.dto.RegisterPolicyVO;
import io.supportflow.user.dto.RegisterRequest;
import io.supportflow.user.dto.RegisterResponse;
import io.supportflow.user.dto.ResetCodeResponse;
import io.supportflow.user.dto.ResetCodeResult;
import io.supportflow.user.dto.ResetPasswordRequest;
import io.supportflow.user.dto.SendRegisterCodeRequest;
import io.supportflow.user.dto.SendResetCodeRequest;
import io.supportflow.user.dto.TwoFactorLoginRequest;
import io.supportflow.user.dto.TwoFactorResendRequest;
import io.supportflow.user.security.CaptchaStore;
import io.supportflow.user.security.CaptchaImageRenderer;
import io.supportflow.user.service.AuthService;
import io.supportflow.user.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业用户注册 / 登录 / 验证码 / 当前用户。
 */
@RestController
@RequestMapping("/api/user/auth")
public class AuthController {

    private final AuthService authService;
    private final CaptchaStore captchaStore;
    private final CaptchaImageRenderer captchaImageRenderer;
    private final PasswordResetService passwordResetService;
    private final boolean captchaDebug;

    public AuthController(
            AuthService authService,
            CaptchaStore captchaStore,
            CaptchaImageRenderer captchaImageRenderer,
            PasswordResetService passwordResetService,
            @Value("${supportflow.auth.captcha-debug:false}") boolean captchaDebug
    ) {
        this.authService = authService;
        this.captchaStore = captchaStore;
        this.captchaImageRenderer = captchaImageRenderer;
        this.passwordResetService = passwordResetService;
        this.captchaDebug = captchaDebug;
    }

    /**
     * 获取图形验证码（登录 / 注册共用）。
     */
    @GetMapping("/captcha")
    public ApiResponse<CaptchaResponse> captcha() {
        String code = captchaStore.randomCode();
        String id = captchaStore.create(code);
        return ApiResponse.ok(new CaptchaResponse(id, captchaImageRenderer.toDataUrl(code),
                captchaDebug ? code : null));
    }

    /**
     * 企业账号注册。
     */
    @PostMapping("/register")
    public ApiResponse<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    /**
     * 注册策略（公开只读）：注册页据此决定"要不要显示邮箱验证码"以及"是否已关闭注册"。
     *
     * <p>只回两个布尔值，不暴露阈值之类的安全参数。</p>
     */
    @GetMapping("/register-policy")
    public ApiResponse<RegisterPolicyVO> registerPolicy() {
        return ApiResponse.ok(authService.registerPolicyView());
    }

    /**
     * 注册：发送邮箱验证码（平台开了「注册需手机 / 邮箱双重验证」时才用得上）。
     *
     * <p>同样要先过图形验证码——否则这个接口就是"给任意邮箱发信的免费工具"。</p>
     */
    @PostMapping("/register/code")
    public ApiResponse<RegisterCodeResponse> sendRegisterCode(
            @Valid @RequestBody SendRegisterCodeRequest request
    ) {
        RegisterCodeResult result = authService.sendRegisterCode(
                request.email(), request.captchaId(), request.captchaCode());
        return ApiResponse.ok(new RegisterCodeResponse(
                result.maskedEmail(), result.expireSeconds(), result.debugCode()));
    }

    /**
     * 账号密码登录。
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        String ip = RequestUtils.clientIp(httpRequest);
        String userAgent = RequestUtils.userAgent(httpRequest);
        return ApiResponse.ok(authService.login(request, ip, userAgent));
    }

    /**
     * 登录第二步：邮箱验证码换 token（平台开了「强制双因素认证」时才有这一步）。
     *
     * <p>挑战号来自第一步的响应；验证码错一次记一次登录失败（与密码错误共用锁定计数）。</p>
     */
    @PostMapping("/login/2fa")
    public ApiResponse<LoginResponse> loginWithTwoFactor(
            @Valid @RequestBody TwoFactorLoginRequest request,
            HttpServletRequest httpRequest
    ) {
        String ip = RequestUtils.clientIp(httpRequest);
        String userAgent = RequestUtils.userAgent(httpRequest);
        return ApiResponse.ok(authService.loginWithTwoFactor(
                request.challengeId(), request.code(), ip, userAgent));
    }

    /**
     * 登录第二步：重发验证码（挑战号不变，60 秒内不能重复发）。
     */
    @PostMapping("/login/2fa/resend")
    public ApiResponse<LoginResponse> resendTwoFactorCode(
            @Valid @RequestBody TwoFactorResendRequest request
    ) {
        return ApiResponse.ok(authService.resendTwoFactorCode(request.challengeId()));
    }

    /**
     * 当前登录用户（前端进入后台后用于恢复会话）。
     */
    @GetMapping("/me")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.ok(authService.me(LoginUserContext.require()));
    }

    /**
     * 修改当前登录用户密码。
     */
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        LoginUser loginUser = LoginUserContext.require();
        authService.changePassword(loginUser, request.oldPassword(), request.newPassword());
        return ApiResponse.ok();
    }

    /**
     * 忘记密码：发送邮箱验证码。
     */
    @PostMapping("/password/reset-code")
    public ApiResponse<ResetCodeResponse> sendResetCode(@Valid @RequestBody SendResetCodeRequest request) {
        ResetCodeResult result = passwordResetService.sendResetCode(request.account());
        return ApiResponse.ok(new ResetCodeResponse(
                result.maskedEmail(),
                result.debugCode(),
                result.expireMinutes()
        ));
    }

    /**
     * 忘记密码：校验验证码并重置密码。
     */
    @PostMapping("/password/reset")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.account(), request.code(), request.newPassword());
        return ApiResponse.ok();
    }

    /**
     * 刷新访问令牌（审核通过等场景，租户编码已变化时无需重新登录）。
     */
    @PostMapping("/refresh-token")
    public ApiResponse<LoginResponse> refreshToken() {
        LoginUser loginUser = LoginUserContext.require();
        return ApiResponse.ok(authService.refreshToken(loginUser));
    }
}
