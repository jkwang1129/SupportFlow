package io.supportflow.customer.dto.quality;

import jakarta.validation.constraints.Size;

/** 处理告警请求体 */
public record HandleAlertBody(@Size(max = 512, message = "处理说明最长 512 个字符") String remark) {
}
