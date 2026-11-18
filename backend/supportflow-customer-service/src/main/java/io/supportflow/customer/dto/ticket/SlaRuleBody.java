package io.supportflow.customer.dto.ticket;

/** SLA 规则入参（按优先级配响应 / 解决时限）。 */
public record SlaRuleBody(Integer priority, Integer firstResponseMinutes, Integer resolveMinutes,
                          Boolean isEnabled) {
}
