package io.supportflow.customer.dto.session;

/**
 * 坐席状态。
 *
 * @param maxConcurrency 最多同时接待几单
 * @param activeCount    当前正在接待几单（坐席看到这个才知道自己是不是接满了）
 */
public record AgentStatusVO(String agentId, int status, String statusText, int maxConcurrency,
                            int activeCount, boolean connected) {
}
