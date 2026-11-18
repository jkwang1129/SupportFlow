package io.supportflow.tenant.dto;

import java.util.List;

/**
 * 租户列表分页。
 */
public record TenantPageVO(long total, int page, int pageSize, List<TenantListItemVO> list) {
}
