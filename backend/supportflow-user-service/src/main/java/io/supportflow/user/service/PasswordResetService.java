package io.supportflow.user.service;

import io.supportflow.common.exception.BizException;
import io.supportflow.common.util.Texts;
import io.supportflow.user.dto.ResetCodeResult;
import io.supportflow.user.entity.SysUser;
import io.supportflow.user.enums.UserStatus;
import io.supportflow.user.mapper.SysUserMapper;
import io.supportflow.user.security.PasswordResetStore;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 忘记密码：邮箱验证码 + 重置密码。
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final SysUserMapper sysUserMapper;
    private final PasswordResetStore resetStore;
    private final MemberMailService mailService;
    private final boolean debug;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public PasswordResetService(
            SysUserMapper sysUserMapper,
            PasswordResetStore resetStore,
            MemberMailService mailService,
            @Value("${supportflow.auth.reset-code-debug:false}") boolean debug
    ) {
        this.sysUserMapper = sysUserMapper;
        this.resetStore = resetStore;
        this.mailService = mailService;
        this.debug = debug;
    }

    /**
     * 发送重置密码验证码。账号不存在时返回相同文案，避免账号枚举。
     */
    public ResetCodeResult sendResetCode(String account) {
        if (account == null || account.isBlank()) {
            throw new BizException(40001, "请输入手机号 / 邮箱 / 用户编号");
        }
        SysUser user = sysUserMapper.findByAccount(account.trim());
        if (user == null) {
            log.info("忘记密码请求账号不存在 account={}", account);
            return new ResetCodeResult("", null, 10);
        }
        if (user.getStatus() != UserStatus.NORMAL.getCode()) {
            throw new BizException(40301, "账号已被停用或锁定，请联系平台运营");
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new BizException(40002, "账号未绑定邮箱，请联系平台管理员重置密码");
        }

        String code = resetStore.issue(user.getId());
        boolean sent = mailService.sendPasswordResetEmail(user.getEmail(), code);
        if (!sent && !debug) {
            throw new BizException(50002, "邮件服务未启用，请联系平台管理员重置密码");
        }
        log.info("重置密码验证码已生成 userId={}, email={}, sent={}", user.getId(), Texts.maskEmail(user.getEmail()), sent);
        return new ResetCodeResult(Texts.maskEmail(user.getEmail()), debug ? code : null, 10);
    }

    /**
     * 校验验证码并重置密码。
     */
    @Transactional
    public void resetPassword(String account, String code, String newPassword) {
        if (account == null || account.isBlank()) {
            throw new BizException(40001, "请输入手机号 / 邮箱 / 用户编号");
        }
        SysUser user = sysUserMapper.findByAccount(account.trim());
        if (user == null) {
            throw new BizException(40002, "验证码错误或已过期");
        }
        if (!resetStore.verify(user.getId(), code)) {
            throw new BizException(40002, "验证码错误或已过期");
        }
        if (user.getPassword() != null && passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new BizException(40002, "新密码不能与原密码相同");
        }
        LambdaUpdateWrapper<SysUser> update = Wrappers.lambdaUpdate(SysUser.class)
                .eq(SysUser::getId, user.getId())
                .eq(SysUser::getDeleted, false)
                .set(SysUser::getPassword, passwordEncoder.encode(newPassword))
                .set(SysUser::getEditor, "PASSWORD_RESET")
                .set(SysUser::getUpdateTime, LocalDateTime.now());
        sysUserMapper.update(null, update);
        log.info("用户通过邮箱验证码重置密码成功 userId={}, userNo={}", user.getId(), user.getUserNo());
    }

}
