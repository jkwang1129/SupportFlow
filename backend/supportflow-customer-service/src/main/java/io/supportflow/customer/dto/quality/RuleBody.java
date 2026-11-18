package io.supportflow.customer.dto.quality;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 质检规则入参。 */
public record RuleBody(
        @Size(max = 40)
        String id,

        @NotBlank(message = "请填写规则名称")
        @Size(max = 64, message = "规则名称最长 64 个字符")
        String ruleName,

        @NotNull(message = "请选择规则类型")
        @Min(1)
        @Max(5)
        Integer ruleType,

        @Size(max = 500, message = "规则内容最长 500 个字符")
        String ruleContent,

        @Min(value = 0, message = "权重不能小于 0")
        @Max(value = 100, message = "权重不能大于 100")
        int weight,

        boolean enabled,

        /** 是否参与实时质检（不传按参与处理） */
        Boolean realtime,

        @Size(max = 512, message = "命中词最长 512 个字符")
        String hitKeywords,

        @Min(value = 1, message = "告警级别取值 1-3")
        @Max(value = 3, message = "告警级别取值 1-3")
        Integer severity,

        /** 仅"5-响应超时"类规则使用：客户发完消息多久没回算超时 */
        @Min(value = 10, message = "响应超时秒数至少 10 秒")
        @Max(value = 3600, message = "响应超时秒数最多 3600 秒")
        Integer timeoutSeconds
) {
}
