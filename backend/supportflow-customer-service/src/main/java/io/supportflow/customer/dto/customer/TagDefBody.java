package io.supportflow.customer.dto.customer;

import java.math.BigDecimal;

/**
 * 标签定义入参（新建 / 修改共用）。
 *
 * <p>规则标签要同时给"写给人看的说明"（ruleHint）和"给引擎跑的四件套"
 * （ruleMetric / ruleOp / ruleValue / ruleWindowDays）：前者是文档，后者才是逻辑。</p>
 */
public record TagDefBody(String tagCode, String tagName, String tagGroup, String color,
                         Integer tagType, String ruleHint, String ruleMetric, String ruleOp,
                         BigDecimal ruleValue, Integer ruleWindowDays, String description,
                         Integer sortNo, Boolean enabled) {
}
