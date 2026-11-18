package io.supportflow.ops.dto;

import java.util.List;

/**
 * 按调用链号查到的 AI 侧明细。
 *
 * @param requestId 就是查的这个号（回显出来，页面上可以直接复制）
 */
public record AiTraceVO(boolean found, List<AiTraceItemVO> items, String requestId) {
}
