package io.supportflow.user.dto;

import java.util.List;

/**
 * 平台角色列表项（前端点一行就能直接勾选权限，所以权限明细一起带回来）。
 *
 * @param id        角色 ID（字符串，避免前端 JS 大整数精度丢失）
 * @param userCount 绑定人数
 * @param permIds   已勾选的权限 ID
 */
public record RoleVO(String id, String roleCode, String roleName, String remark,
                     Boolean isFixed, Boolean isEnabled, List<String> permIds,
                     int permCount, Long userCount) {
}
