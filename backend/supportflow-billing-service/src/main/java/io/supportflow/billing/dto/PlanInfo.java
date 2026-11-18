package io.supportflow.billing.dto;

import java.time.LocalDateTime;

/** 租户当前套餐（planId 为空表示还没开通） */
public record PlanInfo(Long planId, LocalDateTime expireTime, Integer status) {

    /** "查不到"的缺省值：调用方在别的包，所以是 public */
    public static PlanInfo none() {
        return new PlanInfo(null, null, null);
    }
}
