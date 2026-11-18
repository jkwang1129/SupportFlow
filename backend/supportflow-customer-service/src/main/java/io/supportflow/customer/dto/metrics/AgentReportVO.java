package io.supportflow.customer.dto.metrics;

import java.util.List;

/** 报表分页 */
public record AgentReportVO(long total, int page, int pageSize, String from, String to,
                            List<AgentReportRowVO> list) {
}
