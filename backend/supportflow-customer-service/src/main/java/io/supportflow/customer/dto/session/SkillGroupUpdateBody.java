package io.supportflow.customer.dto.session;

import jakarta.validation.constraints.Size;

/** 修改技能组请求体 */
public record SkillGroupUpdateBody(
        @Size(max = 255)
        String description,

        Integer overflowAfterSeconds
) {
}
