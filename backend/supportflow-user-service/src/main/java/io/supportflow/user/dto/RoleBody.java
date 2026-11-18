package io.supportflow.user.dto;

import java.util.List;

/** 角色入参 */
public record RoleBody(String roleCode, String roleName, String remark,
                       Boolean isEnabled, List<Long> permIds) {
}
