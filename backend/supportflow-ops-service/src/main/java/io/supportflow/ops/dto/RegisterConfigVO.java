package io.supportflow.ops.dto;

import java.util.List;

/**
 * 注册审核策略（注册页与注册链路照它执行）。
 */
public record RegisterConfigVO(boolean allowRegister, boolean needReview, Integer trialDays,
                               List<String> autoApproveDomains, boolean requireDoubleVerify,
                               boolean configured) {
}
