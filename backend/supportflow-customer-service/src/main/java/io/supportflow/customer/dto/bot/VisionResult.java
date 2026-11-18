package io.supportflow.customer.dto.bot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 识别结果。
 *
 * @param available  是否真的识别了（false 表示没配密钥 / 调用失败，调用方要降级）
 * @param summary    一句话判读
 * @param ocrText    图里的文字（OCR 原文）
 * @param orderNo    抽出来的订单号
 * @param amount     抽出来的金额
 * @param errorText  报错截图里的报错原文
 * @param needHuman  模型判断"这单该找人工"（涉及退款失败、支付异常、投诉等）
 */
public record VisionResult(
        boolean available,
        String summary,
        String ocrText,
        String orderNo,
        String amount,
        String errorText,
        boolean needHuman,
        String provider,
        String model,
        String hint
) {
    public static VisionResult unavailable(String hint) {
        return new VisionResult(false, "", "", "", "", "", false, "", "", hint);
    }

    public static VisionResult from(Map<String, Object> data) {
        return new VisionResult(
                Boolean.TRUE.equals(data.get("available")),
                str(data.get("summary")),
                str(data.get("ocr_text")),
                str(data.get("order_no")),
                str(data.get("amount")),
                str(data.get("error_text")),
                Boolean.TRUE.equals(data.get("need_human")),
                str(data.get("provider")),
                str(data.get("model")),
                str(data.get("hint")));
    }

    /** 图片消息里回填的识别信息（前端拿它展示"AI 判读"） */
    public Map<String, Object> toMessagePatch() {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("aiSummary", summary);
        patch.put("aiOcrText", ocrText);
        patch.put("aiOrderNo", orderNo);
        patch.put("aiAmount", amount);
    patch.put("aiErrorText", errorText);
    patch.put("aiNeedHuman", needHuman);
    patch.put("aiAvailable", available);
    patch.put("aiModel", model);
    return patch;
}

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
