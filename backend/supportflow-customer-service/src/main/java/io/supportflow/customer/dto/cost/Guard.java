package io.supportflow.customer.dto.cost;

/**
 * 成本闸门的结果：正常 / 降级到规则与检索 / 暂停 AI。
 *
 * <p>调模型之前问一次"这个租户还能不能花"，答案是它；模式常量与它放在一起，
 * 免得"判断在 dto、常量在客户端"两边对不上。</p>
 */
public record Guard(int mode, String reason) {

    /** 正常：照常调模型 */
    public static final int MODE_NORMAL = 1;
    /** 降级：跳过模型，仍按规则与检索把话接住 */
    public static final int MODE_DEGRADE = 2;
    /** 暂停：不调模型，直接转人工 */
    public static final int MODE_BLOCK = 3;

    public static final Guard NORMAL = new Guard(MODE_NORMAL, "未配置成本预算");

    public boolean isDegraded() {
        return mode == MODE_DEGRADE;
    }

    public boolean isBlocked() {
        return mode == MODE_BLOCK;
    }

    /** 要不要跳过模型调用（降级与暂停都跳） */
    public boolean skipModel() {
        return mode == MODE_DEGRADE || mode == MODE_BLOCK;
    }
}
