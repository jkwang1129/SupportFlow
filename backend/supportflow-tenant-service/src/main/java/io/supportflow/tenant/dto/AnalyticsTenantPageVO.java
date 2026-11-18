package io.supportflow.tenant.dto;

import java.util.List;

/**
 * 平台数据页的租户分页。
 */
public record AnalyticsTenantPageVO(int total, int page, int pageSize,
                                    List<AnalyticsTenantRowVO> list) {
}
