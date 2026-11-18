package io.supportflow.customer.service.quality;

import io.supportflow.customer.dto.quality.AiAlertDailyVO;
import io.supportflow.customer.dto.quality.AiQualityOverviewVO;
import io.supportflow.customer.dto.quality.AiQualityVO;
import io.supportflow.customer.dto.quality.AiTopRuleVO;
import io.supportflow.customer.mapper.PlatformAiQualityMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 平台「AI 监控」的质量与安全块：AI 质检结果 + 会话实时质检预警。
 *
 * <p>两个来源都在 customer_db：{@code qa_task}（会话质检任务，含 AI 分与风险等级）
 * 与 {@code qa_alert}（边聊边检的实时预警）。平台看的是聚合：
 * AI 判得准不准（平均分 / 风险分布）、风险拦没拦住（命中数与严重级别）、
 * 哪些规则总被踩（培训与规则调优的风向标）。</p>
 */
@Service
public class PlatformAiQualityService {

    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;
    private static final int TOP_RULES = 5;

    private final PlatformAiQualityMapper qualityMapper;

    public PlatformAiQualityService(PlatformAiQualityMapper qualityMapper) {
        this.qualityMapper = qualityMapper;
    }

    public AiQualityVO quality(int days) {
        int span = days < 1 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        LocalDate from = LocalDate.now().minusDays(span - 1L);

        Map<String, Object> qa = qualityMapper.selectQaOverview(span);
        Map<String, Object> alerts = qualityMapper.selectAlertOverview(span);
        List<Map<String, Object>> daily = qualityMapper.selectAlertDaily(span);
        List<Map<String, Object>> topRules = qualityMapper.selectTopRules(span, TOP_RULES);

        long scored = longValue(qa, "scored_tasks");
        long reviewed = longValue(qa, "reviewed");
        AiQualityOverviewVO overview = new AiQualityOverviewVO(
                longValue(qa, "tasks"), longValue(qa, "scored_tasks"),
                decimalValue(qa, "avg_ai_score"), longValue(qa, "high_risk"),
                longValue(qa, "mid_risk"), longValue(qa, "low_risk"),
                longValue(qa, "reviewed"), longValue(alerts, "alerts"),
                longValue(alerts, "severe"), longValue(alerts, "warning"),
                longValue(alerts, "pending"), longValue(alerts, "tenants"),
                scored == 0 ? 0d : Math.round(reviewed * 1000.0 / scored) / 10.0);
        return new AiQualityVO(span, from.toString(), LocalDate.now().toString(), overview,
                daily.stream().map(row -> new AiAlertDailyVO(String.valueOf(row.get("stat_date")),
                        longValue(row, "alerts"), longValue(row, "severe"))).toList(),
                topRules.stream().map(row -> new AiTopRuleVO(str(row.get("rule_name")),
                        row.get("rule_type"), ruleTypeText(intValue(row.get("rule_type"))),
                        longValue(row, "alerts"), longValue(row, "severe"),
                        text(row.get("last_hit_time")))).toList());
    }

    /** 与实时预警页同一套文案（1-敏感词、2-服务承诺、3-情绪安抚、4-响应超时） */
    private static String ruleTypeText(int ruleType) {
        return switch (ruleType) {
            case 1 -> "敏感词";
            case 2 -> "服务承诺";
            case 3 -> "情绪安抚";
            case 4 -> "响应超时";
            default -> "规则 " + ruleType;
        };
    }

    private static long longValue(Map<String, Object> row, String key) {
        return longValue(row == null ? null : row.get(key));
    }

    private static long longValue(Object raw) {
        if (raw == null) {
            return 0L;
        }
        if (raw instanceof BigDecimal decimal) {
            return decimal.longValue();
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double decimalValue(Map<String, Object> row, String key) {
        Object raw = row == null ? null : row.get(key);
        if (raw == null) {
            return 0d;
        }
        if (raw instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    private static int intValue(Object raw) {
        return (int) longValue(raw);
    }

    /** null 安全的字符串化：SQL 里查不到的列不要变成 "null" 这个字面量 */
    private static String str(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static String text(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw);
        return value.length() >= 19 ? value.replace('T', ' ').substring(0, 19) : value;
    }
}
