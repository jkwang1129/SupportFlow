package io.supportflow.tenant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 网关取某个租户的限额与状态。
 *
 * <p>查不到租户时只回 {@code tenantCode + exists=false}，网关按"很保守的额度"兜底；
 * 所以其余字段在那种情况下不出现（NON_NULL）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TenantQuotaVO(String tenantCode, boolean exists, Integer tenantStatus,
                            Integer isolationMode, Boolean enabled, Integer qpsLimit,
                            Integer dailyRequestLimit, Integer dailySessionLimit,
                            Integer aiDailyLimit) {

    /** 租户不存在时的保守回执 */
    public static TenantQuotaVO unknown(String tenantCode) {
        return new TenantQuotaVO(tenantCode, false, null, null, null, null, null, null, null);
    }
}
