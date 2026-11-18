package io.supportflow.customer.dto.customer;

import java.util.List;

/** 导入结果：逐行容错，所以失败明细要带行号 */
public record ImportResult(int total, int created, int updated, int tagged, List<ImportFailure> failures) {
}
