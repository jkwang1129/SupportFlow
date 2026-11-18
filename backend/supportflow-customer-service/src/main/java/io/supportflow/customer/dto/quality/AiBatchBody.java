package io.supportflow.customer.dto.quality;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 批量 AI 质检入参：要重新初检的任务号。 */
public record AiBatchBody(
        @Size(min = 1, max = 200, message = "请选择 1~200 条质检任务")
        List<@NotBlank(message = "质检任务编号不能为空") @Size(max = 40) String> taskNos
) {
}
