package io.supportflow.customer.dto.ticket;

/** 打开回访表单的结果：状态 + 给客户看的一句话。 */
public record SurveyOutcome(SurveyState state, String message) {
    /** 便捷构造（调用方在别的包，所以要 public） */
    public static SurveyOutcome of(SurveyState state, String message) {
        return new SurveyOutcome(state, message);
    }
}
