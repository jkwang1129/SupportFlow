package io.supportflow.customer.dto.bot;

import java.util.List;

/** 知识问答结果 */
public record AskResult(
        String question,
        String answer,
        /** 编排引擎：langgraph / linear */
        String engine,
        /** 检索口径：vector / keyword-local-vector / keyword */
        String mode,
        /** 召回结果是否够用（不够时回答会保守一些） */
        boolean enough,
        List<CitationVO> citations,
        List<StepVO> steps
) {
}
