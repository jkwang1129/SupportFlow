package io.supportflow.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * 平台账号列表 / 导出的一行（来自 sys_user + 角色聚合）。
 *
 * <p>JSON 字段名保持数据库列名的下划线写法（前端平台用户页按 {@code row.user_no}
 * 取值），这里用 {@link JsonProperty} 显式钉住，避免 Java 侧改个字段名就把页面改挂。</p>
 */
public record PlatformUserRow(
        @JsonProperty("user_id") Long userId,
        @JsonProperty("user_no") String userNo,
        @JsonProperty("name") String name,
        @JsonProperty("phone") String phone,
        @JsonProperty("email") String email,
        @JsonProperty("user_type") Integer userType,
        @JsonProperty("tenant_code") String tenantCode,
        @JsonProperty("status") Integer status,
        @JsonProperty("last_login_time") LocalDateTime lastLoginTime,
        @JsonProperty("create_time") LocalDateTime createTime,
        @JsonProperty("role_ids") String roleIds,
        @JsonProperty("role_codes") String roleCodes,
        @JsonProperty("role_names") String roleNames) {
}
