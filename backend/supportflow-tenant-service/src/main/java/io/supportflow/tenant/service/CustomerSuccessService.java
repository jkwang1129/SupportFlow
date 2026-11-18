package io.supportflow.tenant.service;

import io.supportflow.common.exception.BizException;
import io.supportflow.tenant.dto.CustomerSuccessVO;
import io.supportflow.tenant.dto.HealthModelVO;
import io.supportflow.tenant.dto.HealthScorePartsVO;
import io.supportflow.tenant.dto.TenantHealthSummaryVO;
import io.supportflow.tenant.dto.TenantHealthVO;
import io.supportflow.tenant.mapper.CustomerSuccessMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 客户成功（平台「客户成功」页）：租户健康度、续费风险、扩展商机。
 *
 * <p><b>健康度是算出来的</b>，四项加权（满分 100）：</p>
 * <ol>
 *   <li><b>用量 35%</b>：<b>活跃日的日均请求量</b>分档——注意不是"用量 ÷ 配额"：
 *       配额是上限不是目标，拿它当分母会把付了钱的正常客户判成"有风险"；</li>
 *   <li><b>活跃 25%</b>：近 30 天里有上报的天数占比（分母至少 7 天，新接入不冤判）；</li>
 *   <li><b>到期 20%</b>：剩余天数（≥90 天满分，已过期 0 分）；</li>
 *   <li><b>趋势 20%</b>：最近 7 天 vs 前 7 天请求量——掉头向下是流失前兆。</li>
 * </ol>
 *
 * <p>原型里写的"满意度 25%"这一项没有纳入：满意度在 customer_db 的会话评价里，
 * 属于另一个库；先不跨库取，权重按上面四项重新分配（口径写进代码与页面提示，
 * 不假装有这一项）。</p>
 *
 * <p><b>续费风险</b>与<b>扩展商机</b>也是规则算出来的，运营可以在 ops 侧人工覆盖
 * （{@code ops_db.customer_success.renew_risk / expand_opportunity}）。</p>
 */
@Service
public class CustomerSuccessService {

    /** 免费版套餐 ID（计费库初始化脚本固定写入，与平台数据页同一个口径） */
    private static final long FREE_PLAN_ID = 940000000000000000L;

    /** 健康度权重（合起来 100） */
    private static final int WEIGHT_USAGE = 35;
    private static final int WEIGHT_ACTIVE = 25;
    private static final int WEIGHT_EXPIRE = 20;
    private static final int WEIGHT_TREND = 20;

    /** 用量达到配额的 70% 就值得谈升配 */
    private static final int EXPAND_USAGE_PERCENT = 70;

    /** 30 天内到期算"要盯住了" */
    private static final int RENEW_WATCH_DAYS = 30;

    private final CustomerSuccessMapper mapper;

    public CustomerSuccessService(CustomerSuccessMapper mapper) {
        this.mapper = mapper;
    }

    /** 列表 + 概览（KPI 与列表同一份数据算出来，数字天然自洽） */
    public CustomerSuccessVO overview() {
        List<TenantHealthVO> items = new ArrayList<>();
        int healthy = 0;
        int watch = 0;
        int risky = 0;
        int highRisk = 0;
        int midRisk = 0;
        int opportunities = 0;
        int expiring30 = 0;
        int expired = 0;
        long scoreSum = 0;
        for (Map<String, Object> row : mapper.selectTenantHealth()) {
            TenantHealthVO item = evaluate(row);
            items.add(item);
            String level = item.healthLevel();
            if ("HEALTHY".equals(level)) {
                healthy++;
            } else if ("WATCH".equals(level)) {
                watch++;
            } else {
                risky++;
            }
            int risk = item.renewRisk();
            if (risk == 3) {
                highRisk++;
            } else if (risk == 2) {
                midRisk++;
            }
            if (item.expandOpportunity() == 1) {
                opportunities++;
            }
            Long remain = item.remainDays();
            if (remain != null && remain < 0) {
                expired++;
            } else if (remain != null && remain <= RENEW_WATCH_DAYS) {
                expiring30++;
            }
            scoreSum += item.healthScore();
        }

        TenantHealthSummaryVO summary = new TenantHealthSummaryVO(items.size(), healthy, watch,
                risky, highRisk, midRisk, opportunities, expiring30, expired,
                items.isEmpty() ? 0L : Math.round(scoreSum * 1.0 / items.size()));
        return new CustomerSuccessVO(summary, items, healthModel());
    }

    /** 单个租户的健康度分解（页面点开某行看"为什么是这个分"） */
    public TenantHealthVO detail(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new BizException(40001, "缺少租户编号");
        }
        Map<String, Object> row = mapper.selectTenantHealthDetail(tenantCode.trim());
        if (row == null) {
            throw new BizException(40401, "租户不存在：" + tenantCode);
        }
        return evaluate(row);
    }

    /** 健康度模型说明（页面右上角「健康模型」按钮直接用，避免前端再抄一份口径） */
    public HealthModelVO healthModel() {
        return new HealthModelVO(WEIGHT_USAGE, WEIGHT_ACTIVE, WEIGHT_EXPIRE, WEIGHT_TREND,
                EXPAND_USAGE_PERCENT, RENEW_WATCH_DAYS,
                "用量 " + WEIGHT_USAGE + "% + 活跃 " + WEIGHT_ACTIVE + "% + 到期 "
                + WEIGHT_EXPIRE + "% + 趋势 " + WEIGHT_TREND + "%。"
                + "用量 = 活跃日日均请求量分档（不看配额占比：配额是上限不是目标）；"
                + "活跃 = 近 30 天有上报的天数占比（分母至少 7 天，刚接入不冤判）；"
                + "到期 = 剩余天数（90 天以上满分）；趋势 = 最近 7 天 vs 前 7 天。"
                + "新接入或刚开始上报用量的租户，活跃分会随数据积累自然回升。"
                + "原型里的「满意度」在 customer_db 的会话评价里，暂未纳入（不跨库取数）。");
    }

    // ------------------------------------------------------------------ 计算

    private TenantHealthVO evaluate(Map<String, Object> row) {
        long requests30 = longValue(row.get("requests_30d"));
        long sessions30 = longValue(row.get("sessions_30d"));
        long aiCalls30 = longValue(row.get("ai_calls_30d"));
        int activeDays = intValue(row.get("active_days_30d"));
        long last7 = longValue(row.get("requests_last7"));
        long prev7 = longValue(row.get("requests_prev7"));
        int dailyQuota = intValue(row.get("daily_request_limit"));
        int aiQuota = intValue(row.get("ai_daily_limit"));
        long planId = longValue(row.get("plan_id"));
        int tenantStatus = intValue(row.get("tenant_status"));
        Long remainDays = remainDays(row.get("expire_time"));

        // ① 用量：看"活跃日的日均请求量"有多深（分档），不看配额占比——
        //    配额是上限不是目标，用它当分母会把正常付费客户判成有风险。
        long dailyAverage = activeDays > 0 ? requests30 / activeDays : 0;
        int usageScore = usageDepthScore(dailyAverage);
        // ② 活跃：近 30 天有上报的天数占比；分母至少 7 天，避免刚接入的租户被冤判
        int activeBase = Math.max(7, Math.min(30, daysSince(row.get("create_time"))));
        int activeScore = clamp((int) Math.round(activeDays * 100.0 / activeBase));
        // ③ 到期：≥90 天满分，0 天 30 分，已过期 0 分（不给负分）
        int expireScore;
        if (remainDays == null) {
            expireScore = 70;   // 没设到期时间（比如还没开套餐）给个中性分，不算健康也不算危险
        } else if (remainDays <= 0) {
            expireScore = 0;
        } else {
            expireScore = clamp((int) Math.round(Math.min(90, remainDays) * 100.0 / 90));
        }
        // ④ 趋势：最近 7 天 vs 前 7 天，持平 70 分，翻倍 100，掉到 0 给 20（不直接判死）
        int trendScore;
        if (prev7 == 0 && last7 == 0) {
            trendScore = 40;    // 两周都没量：不是"持平"，是没在用
        } else if (prev7 == 0) {
            trendScore = 90;    // 新起来的使用
        } else {
            double ratio = last7 * 1.0 / prev7;
            trendScore = clamp((int) Math.round(Math.min(1.5, ratio) / 1.5 * 100));
        }

        int score = clamp((usageScore * WEIGHT_USAGE + activeScore * WEIGHT_ACTIVE
                + expireScore * WEIGHT_EXPIRE + trendScore * WEIGHT_TREND) / 100);
        String healthLevel = score >= 75 ? "HEALTHY" : score >= 55 ? "WATCH" : "RISK";

        int renewRisk = renewRisk(score, remainDays, tenantStatus, activeDays, last7 + prev7);
        int expandOpportunity = expandOpportunity(planId, tenantStatus, requests30, sessions30,
                aiCalls30, dailyQuota, aiQuota);

        return new TenantHealthVO(str(row.get("tenant_code")),
                row.get("company_name") == null ? str(row.get("tenant_code")) : str(row.get("company_name")),
                str(row.get("industry")),
                row.get("plan_id") == null ? null : String.valueOf(row.get("plan_id")),
                planId == FREE_PLAN_ID, tenantStatus, tenantStatusText(tenantStatus),
                text(row.get("expire_time")), remainDays, requests30, sessions30, aiCalls30,
                activeDays, last7, prev7,
                row.get("last_active_date") == null ? null : String.valueOf(row.get("last_active_date")),
                score, healthLevel, healthLevelText(healthLevel),
                new HealthScorePartsVO(usageScore, activeScore, expireScore, trendScore),
                renewRisk, riskText(renewRisk),
                renewRiskReason(renewRisk, score, remainDays, activeDays, last7 + prev7),
                expandOpportunity, expandOpportunity == 1 ? "有" : "无",
                expandSuggestion(expandOpportunity, planId, requests30, dailyQuota, aiCalls30, aiQuota));
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 续费风险：3-高、2-中、1-低。
     *
     * <p>规则直白：已经过期 / 一周内到期 / 快到期又不健康 → 高；
     * 一个月内到期、健康分偏低、近两周没有用量 → 中；其余低。</p>
     */
    private int renewRisk(int score, Long remainDays, int tenantStatus, int activeDays, long recentRequests) {
        if (tenantStatus == 2 || tenantStatus == 4) {
            return 3;   // 已冻结 / 已注销：不用谈风险了，直接进高优先级名单
        }
        if (remainDays != null) {
            if (remainDays <= 7) {
                return 3;
            }
            if (remainDays <= RENEW_WATCH_DAYS) {
                return score < 60 ? 3 : 2;
            }
        }
        if (score < 55 || (activeDays == 0)) {
            return 2;
        }
        if (recentRequests == 0) {
            return 2;
        }
        return 1;
    }

    private String renewRiskReason(int risk, int score, Long remainDays, int activeDays, long recentRequests) {
        if (risk == 1) {
            return "用量与活跃正常，到期还有 " + (remainDays == null ? "—" : remainDays + " 天");
        }
        List<String> reasons = new ArrayList<>();
        if (remainDays != null && remainDays <= 7) {
            reasons.add(remainDays < 0 ? "已过期" : "还剩 " + remainDays + " 天到期");
        } else if (remainDays != null && remainDays <= RENEW_WATCH_DAYS) {
            reasons.add("一个月内到期（还剩 " + remainDays + " 天）");
        }
        if (score < 55) {
            reasons.add("健康分偏低（" + score + "）");
        }
        if (activeDays == 0) {
            reasons.add("近 30 天没有用量上报");
        }
        if (recentRequests == 0) {
            reasons.add("近两周没有用量");
        }
        return reasons.isEmpty() ? "综合健康度偏低" : String.join("；", reasons);
    }

    /**
     * 扩展商机：1-有、2-无。
     *
     * <p>三种典型：免费版但真的在用（转付费）、用量接近配额（升配）、AI 调用接近日配额（加档）。</p>
     */
    private int expandOpportunity(long planId, int tenantStatus, long requests30, long sessions30,
                                  long aiCalls30, int dailyQuota, int aiQuota) {
        if (tenantStatus != 1) {
            return 2;   // 冻结/注销的租户先谈恢复，不谈扩展
        }
        boolean active = requests30 > 0 || sessions30 > 0 || aiCalls30 > 0;
        if (planId == FREE_PLAN_ID && active) {
            return 1;
        }
        long monthQuota = (dailyQuota > 0 ? dailyQuota : 0) * 30L;
        if (monthQuota > 0 && requests30 * 100 >= monthQuota * EXPAND_USAGE_PERCENT) {
            return 1;
        }
        long monthAiQuota = (aiQuota > 0 ? aiQuota : 0) * 30L;
        if (monthAiQuota > 0 && aiCalls30 * 100 >= monthAiQuota * EXPAND_USAGE_PERCENT) {
            return 1;
        }
        return 2;
    }

    private String expandSuggestion(int opportunity, long planId, long requests30,
                                    int dailyQuota, long aiCalls30, int aiQuota) {
        if (opportunity != 1) {
            return null;
        }
        if (planId == FREE_PLAN_ID) {
            return "免费版正在产生真实用量，建议推动转付费";
        }
        long monthQuota = (dailyQuota > 0 ? dailyQuota : 0) * 30L;
        if (monthQuota > 0 && requests30 * 100 >= monthQuota * EXPAND_USAGE_PERCENT) {
            return "请求量已达月配额 " + (requests30 * 100 / monthQuota) + "%，建议谈升配";
        }
        long monthAiQuota = (aiQuota > 0 ? aiQuota : 0) * 30L;
        if (monthAiQuota > 0 && aiCalls30 * 100 >= monthAiQuota * EXPAND_USAGE_PERCENT) {
            return "AI 调用已达月配额 " + (aiCalls30 * 100 / monthAiQuota) + "%，建议加 AI 档位";
        }
        return "有扩展空间，建议回访确认";
    }

    private static Long remainDays(Object expireTime) {
        if (expireTime == null) {
            return null;
        }
        LocalDate expire;
        if (expireTime instanceof Timestamp timestamp) {
            expire = timestamp.toLocalDateTime().toLocalDate();
        } else if (expireTime instanceof LocalDateTime dateTime) {
            expire = dateTime.toLocalDate();
        } else {
            String value = String.valueOf(expireTime);
            try {
                expire = LocalDateTime.parse(value.replace(' ', 'T').substring(0, 19)).toLocalDate();
            } catch (Exception e) {
                return null;
            }
        }
        return ChronoUnit.DAYS.between(LocalDate.now(), expire);
    }

    private static int daysSince(Object createTime) {
        Long remain = remainDays(createTime);
        return remain == null ? 30 : (int) Math.max(1, -remain);
    }

    /**
     * 用量深度分档：按"活跃日每天用了多少次请求"给分。
     *
     * <p>为什么不用"用量 ÷ 配额"：配额是上限，绝大多数客户一辈子也用不到 100%，
     * 拿它当分母会把正常付费客户算成"有风险"。这里回答的是另一个问题——
     * <b>这家租户到底把客服系统用起来了没有</b>。</p>
     */
    private static int usageDepthScore(long perActiveDay) {
        if (perActiveDay >= 1000) {
            return 100;
        }
        if (perActiveDay >= 300) {
            return 85;
        }
        if (perActiveDay >= 100) {
            return 70;
        }
        if (perActiveDay >= 30) {
            return 55;
        }
        if (perActiveDay >= 10) {
            return 40;
        }
        return perActiveDay > 0 ? 25 : 0;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String healthLevelText(String level) {
        return switch (level) {
            case "HEALTHY" -> "健康";
            case "WATCH" -> "需关注";
            default -> "有风险";
        };
    }

    private static String riskText(int risk) {
        return switch (risk) {
            case 3 -> "高";
            case 2 -> "中";
            default -> "低";
        };
    }

    private static String tenantStatusText(int status) {
        return switch (status) {
            case 1 -> "正常";
            case 2 -> "冻结";
            case 3 -> "到期";
            case 4 -> "注销";
            default -> "状态 " + status;
        };
    }

    private static int intValue(Object raw) {
        return (int) longValue(raw);
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

    private static String text(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw);
        return value.length() >= 19 ? value.replace('T', ' ').substring(0, 19) : value;
    }
}
