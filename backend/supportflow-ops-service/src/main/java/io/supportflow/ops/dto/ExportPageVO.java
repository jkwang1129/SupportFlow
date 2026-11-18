package io.supportflow.ops.dto;

import java.util.List;

/**
 * 导出申请单分页 + 概览。
 */
public record ExportPageVO(int total, int page, int pageSize, List<ExportRowVO> list,
                           ExportSummaryVO summary) {
}
