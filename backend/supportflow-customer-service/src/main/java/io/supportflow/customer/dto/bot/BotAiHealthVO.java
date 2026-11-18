package io.supportflow.customer.dto.bot;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 机器人链路体检里「supportflow-ai 那一侧」的探活结果。
 *
 * @param reachable AI 服务通不通
 * @param status    HTTP 状态码，只有连上了才有
 * @param detail    AI 侧返回的出错详情（结构由对方决定，原样带回）
 * @param hint      一句话下一步该怎么办（连不通 / 版本旧时才有）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BotAiHealthVO(Boolean reachable, Integer status, String baseUrl, String hint,
                            Object detail) {
}
