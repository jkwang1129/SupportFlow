package io.supportflow.customer.dto.quality;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 批量复核入参（一次处理多条任务）。 */
public record BatchReviewBody(
        @Size(min = 1, max = 200, message = "请选择 1~200 条质检任务")
        List<@NotBlank(message = "质检任务编号不能为空") @Size(max = 40) String> taskNos,

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
