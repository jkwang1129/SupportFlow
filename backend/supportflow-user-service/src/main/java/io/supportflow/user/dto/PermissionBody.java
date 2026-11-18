package io.supportflow.user.dto;

import java.util.List;

/** 权限保存入参 */
public record PermissionBody(List<Long> permIds) {
}
