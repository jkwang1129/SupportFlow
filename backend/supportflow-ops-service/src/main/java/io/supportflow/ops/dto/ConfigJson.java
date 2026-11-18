package io.supportflow.ops.dto;

import java.util.List;

/** config_json 的结构（列没必要单开的项放这里） */
public record ConfigJson(List<String> autoApproveDomains, Boolean requireDoubleVerify) {
}
