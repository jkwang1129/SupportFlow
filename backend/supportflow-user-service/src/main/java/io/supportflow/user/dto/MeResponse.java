package io.supportflow.user.dto;

import java.util.List;

/**
 * 当前登录用户（前端进后台后靠它恢复会话）。
 *
 * <p>比登录响应多了 {@code roles} 与 {@code permissions}：前端左侧菜单要按权限过滤，
 * 而权限是「角色 → 权限」算出来的，只能在服务端算好再下发。</p>
 *
 * <p>{@code permissions} 里出现 {@code "*"} 表示**超管**（没有绑定任何平台角色的平台账号，
 * 见 PlatformRoleService.permissionsOf 的兜底说明）。企业账号不下发权限，
 * 保持它现有的「按 userType + 企业角色」判断逻辑不变。</p>
 */
public record MeResponse(
        long userId,
        String userNo,
        String name,
        Integer userType,
        String tenantCode,
        List<String> roles,
        List<String> permissions
) {
}
