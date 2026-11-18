package io.supportflow.customer.service.bot;

import io.supportflow.customer.dto.bot.VisionResult;
import io.supportflow.customer.internal.VisionAiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.supportflow.customer.service.session.SessionAttachmentService;

/**
 * 图片识别的业务编排：取图 → 压图 → 交给视觉模型 → 把结论交给客服大脑。
 *
 * <p>它自己不"看"图，只做三件事：</p>
 * <ol>
 *   <li>把图片从对象存储取出来、压成模型能吃的 JPEG data URL（{@link SessionAttachmentService}）；</li>
 *   <li>调 supportflow-ai 的视觉接口；</li>
 *   <li>把"识别结论"整理成一句能塞进对话的问题上下文，交给客服大脑去做意图判断与回答。</li>
 * </ol>
 *
 * <p>降级原则和第 22 篇一致：**识别不了就如实说，不编**。
 * 没配视觉密钥时返回的 `available=false`，调用方据此给客户一句"暂时看不了图片，已为你转人工/请描述一下"。</p>
 */
@Service
public class VisionService {

    private static final Logger log = LoggerFactory.getLogger(VisionService.class);

    private final SessionAttachmentService attachmentService;
    private final VisionAiClient aiClient;

    public VisionService(SessionAttachmentService attachmentService, VisionAiClient aiClient) {
        this.attachmentService = attachmentService;
        this.aiClient = aiClient;
    }

    /**
     * 识别一组图片。
     *
     * @param question 客户随图说的一句话（可能为空）
     * @return 识别结果；AI 不可用时返回 {@link VisionResult#unavailable}，调用方走降级
     */
    public VisionResult recognize(String tenantCode, String sessionNo, List<Long> fileIds, String question) {
        List<String> images;
        // 两段时间分开计时：客户发完图"感觉慢"时，先看是卡在"下载 + 压缩"还是卡在模型，
        // 别一上来就怀疑模型——3 张图 2 秒里有一半可能是本地预处理
        long prepareStarted = System.currentTimeMillis();
        try {
            images = attachmentService.toVisionImages(fileIds);
        } catch (Exception e) {
            log.warn("读取聊天图片失败 tenant={} session={} error={}", tenantCode, sessionNo, e.getMessage());
            return VisionResult.unavailable("图片读取失败：" + e.getMessage());
        }
        long preparedCost = System.currentTimeMillis() - prepareStarted;
        if (images.isEmpty()) {
            return VisionResult.unavailable("没有可识别的图片");
        }
        try {
            // 把"客户随图说的那句话"也打出来：客户发的图和他说的话，是判断"答得对不对"的两个前提
            log.info("[图片识别] 开始识别 tenant={} session={} 张数={} 预处理={}ms（并发） 随图那句话={}",
                    tenantCode, sessionNo, images.size(),
                    preparedCost,
                    question == null || question.isBlank() ? "（客户没写字，只发了图）" : question);
            long modelStarted = System.currentTimeMillis();
            Map<String, Object> result = aiClient.recognize(tenantCode, sessionNo, images, question);
            long modelCost = System.currentTimeMillis() - modelStarted;
            VisionResult vision = VisionResult.from(result);
            log.info("[图片识别] 结果 tenant={} session={} 可用={} 模型={} 耗时={}ms（预处理 {}ms + 模型 {}ms）\n"
                            + "  判读：{}\n  订单号：{} 金额：{} 建议人工：{}\n  图中文字（{}字）：\n{}",
                    tenantCode, sessionNo, vision.available(), vision.model(),
                    preparedCost + modelCost, preparedCost, modelCost,
                    vision.summary().isEmpty() ? "（无）" : vision.summary(),
                    vision.orderNo().isEmpty() ? "（无）" : vision.orderNo(),
                    vision.amount().isEmpty() ? "（无）" : vision.amount(),
                    vision.needHuman(), vision.ocrText().length(), indent(vision.ocrText()));
            return vision;
        } catch (Exception e) {
            log.warn("图片识别失败 tenant={} session={} error={}", tenantCode, sessionNo, e.getMessage());
            return VisionResult.unavailable(e.getMessage());
        }
    }

    /**
     * 把识别结果整理成"喂给客服大脑"的上下文。
     *
     * <p>为什么要拼成这种格式：大脑那边是按"客户说了什么"来判断意图、检索知识的。
     * 图片本身没法直接参与检索，所以把**OCR 原文 + 判读结论 + 抽出来的订单号/金额**拼成一段文字，
     * 让它等价于"客户把图里的内容打出来发给我们"——这样意图识别、知识库检索、引用溯源全都能照常工作。</p>
     */
    public String toBrainQuestion(VisionResult result, String question) {
        StringBuilder builder = new StringBuilder();
        if (question != null && !question.isBlank()) {
            builder.append(question.trim());
        }
        builder.append("\n[客户发来一张图片]");
        if (!result.available()) {
            builder.append("（图片识别暂不可用：").append(result.hint()).append("）");
            return builder.toString();
        }
        if (!result.summary().isBlank()) {
            builder.append(" 判读：").append(result.summary());
        }
        if (!result.orderNo().isBlank()) {
            builder.append(" 订单号：").append(result.orderNo());
        }
        if (!result.amount().isBlank()) {
            builder.append(" 金额：").append(result.amount());
        }
        if (!result.errorText().isBlank()) {
            builder.append(" 报错原文：").append(result.errorText());
        }
        // 模型判了"这单该找人工"（退款失败 / 支付异常 / 投诉这类）就把这句话也带上：
        // 大脑转不转人工由它自己决定，但信号不能吞掉——截图是敏感问题时，客户那几行字往往看不出来
        if (result.needHuman()) {
            builder.append("\n（这张图涉及退款失败、支付异常或投诉这类敏感情形，建议人工介入）");
        }
        if (!result.ocrText().isBlank()) {
            builder.append("\n图中文字：").append(truncate(result.ocrText(), 600));
        }
        String questionText = builder.toString();
        // 把"真正喂给大脑的那段话"打进日志：客户发图后，机器人答得对不对，
        // 第一件事就是看它到底收到了什么（图里的文字有没有被带进去）
        log.info("[图片识别] 交给大脑的上下文（{}字）：\n{}",
                questionText.length(), indent(questionText));
        return questionText;
    }

    /** 多行文本缩进后打进日志（截断），方便和客户发的图逐行对照 */
    private String indent(String value) {
        if (value == null || value.isBlank()) {
            return "  （没识别到文字）";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return "  " + (text.length() <= 500 ? text : text.substring(0, 500) + "…");
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
