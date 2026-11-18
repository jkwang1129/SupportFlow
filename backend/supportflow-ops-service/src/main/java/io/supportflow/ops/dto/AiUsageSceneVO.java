package io.supportflow.ops.dto;

/**
 * AI 用量按场景的一项（大脑 / 质检 / 检索 / 视觉…）。
 */
public record AiUsageSceneVO(Integer scene, String sceneText, Integer calls, Integer tokens,
                             Double cost) {
}
