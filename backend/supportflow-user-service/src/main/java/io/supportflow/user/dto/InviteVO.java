package io.supportflow.user.dto;

/**
 * 邀请记录项。
 */
public record InviteVO(
        String id,
        String inviteCode,
        String phone,
        String email,
        String roleCode,
        String roleName,
        String inviterName,
        String usedByName,
        int status,
        String statusText,
        String expireTime,
        String createTime
) {
}
