package io.supportflow.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * security_policy 平台安全策略（全平台一行）。

 * <p>原则：**每一项都要有执行点**。六项的落点：登录失败锁定与双因素在 user-service 登录链路、
 * IP 白名单在登录接口、导出审批在导出接口、水印与空闲登出在前端。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("security_policy")
public class SecurityPolicy {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 登录连续失败几次后锁定（0 = 不锁定） */
    private Short loginFailThreshold;

    /** 锁定多少分钟（到点自动解锁） */
    private Short loginLockMinutes;

    /** 管理端登录 IP 白名单开关 */
    private Boolean ipWhitelistEnabled;

    /** 白名单，逗号分隔 */
    private String ipWhitelist;

    /** 敏感导出是否需要审批 */
    private Boolean exportNeedApproval;

    /** 管理端水印 */
    private Boolean watermarkEnabled;

    /** 空闲多久自动登出（分钟） */
    private Short sessionTimeoutMinutes;

    /** 登录双因素（二次验证）开关：密码通过后再校验邮箱验证码 */
    private Boolean twoFactorRequired;

    /** 双因素覆盖范围：1-仅平台账号与企业管理员、2-全部账号（含坐席） */
    private Short twoFactorScope;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
