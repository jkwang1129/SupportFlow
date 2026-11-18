package io.supportflow.customer.dto.channel;

import java.util.List;

/**
 * 400 通话记录分页 + 今日概况。
 */
public record CallPageVO(long total, int page, int pageSize, CallSummaryVO summary,
                         List<CallRowVO> list) {
}
