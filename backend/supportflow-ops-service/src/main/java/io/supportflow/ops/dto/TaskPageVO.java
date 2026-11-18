package io.supportflow.ops.dto;

import java.util.List;

/**
 * 回访任务分页 + 概览。
 */
public record TaskPageVO(int total, int page, int pageSize, List<TaskItemVO> list,
                         TaskSummaryVO summary) {
}
