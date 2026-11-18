package io.supportflow.customer.dto.quality;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 人工复核入参：1-通过、2-驳回、3-重检。 */
public record ReviewBody(
        @NotNull(message = "请选择复核动作")
        @Min(1)
        @Max(3)
        Integer action,

        @Min(value = 0, message = "复核评分不能小于 0")
        @Max(value = 100, message = "复核评分不能大于 100")
        Double score,

        @Size(max = 512, message = "复核意见最长 512 个字符")
        String comment
) {
}
