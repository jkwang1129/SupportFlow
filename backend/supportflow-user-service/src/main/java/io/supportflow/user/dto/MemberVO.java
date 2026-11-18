package io.supportflow.user.dto;

/**
 * 成员列表项。
 */
public record MemberVO(
        long userId,
        String userNo,
        String name,
        String phone,
        String email,
        String avatar,
        String roleCode,
        String roleName,
        int status,
        String lastLoginTime,
        String joinTime
) {
}
