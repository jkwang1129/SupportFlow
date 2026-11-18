package io.supportflow.customer.dto.ticket;

import java.util.List;

/** 列表分页结果：total + 当前页数据（前端用它渲染 el-pagination） */
public record TicketPageVO(long total, int page, int pageSize, List<TicketVO> list) {
}
