package io.supportflow.user.dto;

/**
 * 接受邀请结果。
 */
public record AcceptResult(
        long userId,
        String userNo,
        String name,
        String phone,
        String email,
        String roleCode,
        String roleName,
        String tenantCode
) {
}
