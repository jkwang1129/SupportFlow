package io.supportflow.customer.dto.customer;

import java.util.List;

/** 列表分页 */
public record CustomerPageVO(long total, int page, int pageSize, List<CustomerVO> list) {
}
