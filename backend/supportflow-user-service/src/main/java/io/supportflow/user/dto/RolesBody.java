package io.supportflow.user.dto;

import java.util.List;

/** 分配角色入参 */
public record RolesBody(List<Long> roleIds) {
}
