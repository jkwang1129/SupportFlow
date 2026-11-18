package io.supportflow.ops.dto;

import java.util.List;

/**
 * 告警规则列表 + 指标下拉选项（前端规则编辑器用）。
 */
public record AlertRuleListVO(List<AlertRuleVO> items, List<OptionVO> metrics) {
}
