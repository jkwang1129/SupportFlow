package io.supportflow.customer.dto.metrics;

/** 数据报表权限 */
public record AccessVO(boolean canView, String roleCode, String roleName, String hint) {
}
