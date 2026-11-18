package io.supportflow.user.dto;

/**
 * 账号管理里可分配的角色项（平台账号看平台角色，企业账号看本租户的企业角色）。
 */
public record AssignableRoleVO(String id, String roleCode, String roleName, Integer roleType) {
}
