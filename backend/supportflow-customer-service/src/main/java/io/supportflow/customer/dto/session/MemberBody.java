package io.supportflow.customer.dto.session;

import java.util.List;

/** 设置成员请求体 */
public record MemberBody(List<Long> userIds) {
}
