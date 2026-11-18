package io.supportflow.customer.dto.ticket;

/** SLA 规则（按优先级配响应 / 解决时限）。 */
public record SlaRuleVO(Integer priority, String priorityText, Integer firstResponseMinutes,
                        Integer resolveMinutes, boolean enabled) {
}
