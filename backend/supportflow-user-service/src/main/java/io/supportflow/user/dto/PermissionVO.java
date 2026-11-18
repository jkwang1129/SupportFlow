package io.supportflow.user.dto;

/**
 * 权限目录项（前端「角色与模块权限」的勾选来源，按 sort_no 排）。
 */
public record PermissionVO(String id, String permCode, String permName, Integer permType,
                           String parentId, Integer sortNo) {
}
