package io.supportflow.tenant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * tenant_security_event 租户安全事件。
 *
 * <p>四类：租户头与令牌不一致、跨租户资源访问被拒、被限流拒绝、RLS 拦截。
 * 平台侧「租户管理」里有专门的告警列表——隔离出问题时，这里应该先有动静。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tenant_security_event")
public class TenantSecurityEvent {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 1-租户头与令牌不一致、2-跨租户资源访问、3-被限流拒绝、4-RLS 拦截 */
    private Integer eventType;

    private String detail;

    private String requestUri;

    private String requestMethod;

    private String clientIp;

    private String tokenTenant;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime eventTime;

    private LocalDateTime createTime;
}
