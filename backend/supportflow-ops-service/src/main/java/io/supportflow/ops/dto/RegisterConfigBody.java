package io.supportflow.ops.dto;

import java.util.List;

/** 注册审核策略入参 */
public record RegisterConfigBody(Boolean allowRegister, Boolean needReview, Integer trialDays,
                                 List<String> autoApproveDomains, Boolean requireDoubleVerify) {
}
