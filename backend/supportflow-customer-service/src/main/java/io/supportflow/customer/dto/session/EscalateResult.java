package io.supportflow.customer.dto.session;

/**
 * 转人工的结果。
 *
 * @param notice  需要立刻推给客户的提示（没有就为 null）
 * @param agentId 实际接手的坐席；为 null 表示"还在队列里等"——
 *                调用方据此决定要不要告诉客户"当前没人，已排队"，而不是让客户无限等一句话
 */
public record EscalateResult(MessageVO notice, Long agentId) {
    public static final EscalateResult EMPTY = new EscalateResult(null, null);

    public boolean assigned() {
        return agentId != null;
    }
}
