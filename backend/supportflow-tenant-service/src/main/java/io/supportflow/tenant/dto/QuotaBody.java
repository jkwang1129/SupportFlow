package io.supportflow.tenant.dto;

/** 配额调整入参（null = 不改这一项） */
public record QuotaBody(Integer qpsLimit, Integer dailyRequestLimit,
                        Integer dailySessionLimit, Integer aiDailyLimit,
                        Boolean enabled, String remark) {
}
