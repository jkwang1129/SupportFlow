package io.supportflow.ops.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 本租户「已通过且还没导出过」的那张申请单。
 *
 * <p>{@code found=false, used=true} 表示"已经导过了"——与"还没申请"要给企业的下一步
 * 动作完全不同，所以两个标志都要给。没找到时后面三个字段不出现。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportPendingVO(boolean found, boolean used, String exportId,
                              String scopeJson, String approveTime) {
}
