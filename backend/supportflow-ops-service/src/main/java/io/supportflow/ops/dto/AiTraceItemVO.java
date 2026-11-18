package io.supportflow.ops.dto;

/**
 * 调用链里 AI 侧的一次调用（只出计量与状态，不出对话内容）。
 */
public record AiTraceItemVO(String id, String tenantCode, String modelKey, Integer scene,
                            String sceneText, Integer inputTokens, Integer outputTokens,
                            Double cost, Integer latencyMs, Boolean success, String requestId,
                            String remark, String time) {
}
