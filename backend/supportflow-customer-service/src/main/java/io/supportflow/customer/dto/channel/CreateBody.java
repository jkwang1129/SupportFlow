package io.supportflow.customer.dto.channel;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 新增渠道接入入参。 */
public record CreateBody(
        // 1-网站 2-公众号 3-小程序 4-App 5-400热线 6-邮件（与 ChannelOnboardingService 的类型码一致，
        // 原来写的是 @Max(3)，六条渠道接入后这条声明没跟着改）
        @Min(1) @Max(6)
        int channelType,
        @NotBlank(message = "渠道名称不能为空")
        @Size(max = 64)
        String name,
        @Size(max = 255)
        String desc
) {
}
