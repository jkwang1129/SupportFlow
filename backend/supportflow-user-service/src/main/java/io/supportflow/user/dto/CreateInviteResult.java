package io.supportflow.user.dto;

import java.time.LocalDateTime;

/**
 * 创建邀请结果。
 */
public record CreateInviteResult(
        String id,
        String inviteCode,
        String roleCode,
        String roleName,
        LocalDateTime expireTime,
        String phone,
        String email,
        boolean emailSent
) {
}
