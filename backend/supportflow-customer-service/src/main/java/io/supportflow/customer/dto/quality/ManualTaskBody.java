package io.supportflow.customer.dto.quality;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 手工建质检任务的入参（抽检用）。 */
public record ManualTaskBody(
        @NotBlank(message = "请填写质检会话名称")
        @Size(max = 128, message = "质检会话名称最长 128 个字符")
        String sessionName,

        @NotBlank(message = "请填写接待客服")
        @Size(max = 64, message = "接待客服最长 64 个字符")
        String agentName,

        @Min(value = 0, message = "AI 初检分不能小于 0")
        @Max(value = 100, message = "AI 初检分不能大于 100")
        Double aiScore,

        @Min(1)
        @Max(3)
        Integer riskLevel,

        @Size(max = 512, message = "质检备注最长 512 个字符")
        String comment,

        List<@Size(max = 64) String> ruleNames
) {
}
