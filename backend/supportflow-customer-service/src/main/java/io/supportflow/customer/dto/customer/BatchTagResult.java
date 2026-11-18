package io.supportflow.customer.dto.customer;

import java.util.List;

/** 批量打标结果 */
public record BatchTagResult(int requested, int tagged, int skipped, List<String> missing) {
}
