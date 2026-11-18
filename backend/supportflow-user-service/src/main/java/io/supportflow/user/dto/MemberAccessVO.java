package io.supportflow.user.dto;

/**
 * 当前成员访问信息。
 */
public record MemberAccessVO(boolean canManage, String roleCode, String roleName) {
}
