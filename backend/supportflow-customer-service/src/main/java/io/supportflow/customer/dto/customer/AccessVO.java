package io.supportflow.customer.dto.customer;

/** 当前登录人的客户 360 权限 */
public record AccessVO(String roleCode, String roleName, boolean canOperate, boolean canManageTag,
                       boolean canSeePhone, String hint) {
}
