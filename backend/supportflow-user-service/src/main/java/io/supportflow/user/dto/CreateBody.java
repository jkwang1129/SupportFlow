package io.supportflow.user.dto;

import java.util.List;

/** 新建入参 */
public record CreateBody(Integer userType, String name, String phone, String email,
                         String tenantCode, String password, List<Long> roleIds) {
}
