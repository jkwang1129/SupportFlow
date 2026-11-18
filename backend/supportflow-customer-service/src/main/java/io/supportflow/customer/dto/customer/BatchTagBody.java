package io.supportflow.customer.dto.customer;

import java.util.List;

/** 批量打标入参 */
public record BatchTagBody(List<String> customerNos, Long tagId, String remark) {
}
