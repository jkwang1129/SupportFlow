package io.supportflow.tenant.service;

import io.supportflow.tenant.dto.AnalyticsOverviewVO;
import io.supportflow.tenant.dto.AnalyticsTenantPageVO;
import io.supportflow.tenant.dto.AnalyticsTenantRowVO;
import io.supportflow.tenant.dto.GrowthPointVO;
import io.supportflow.tenant.dto.PlanDistributionVO;
import io.supportflow.tenant.dto.PlatformAnalyticsVO;
import io.supportflow.tenant.dto.StatusDistributionVO;
import io.supportflow.tenant.dto.TopTenantVO;
import io.supportflow.tenant.mapper.PlatformAnalyticsMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台经营分析（平台数据页）：租户增长、套餐分布、用量 Top、租户列表。
 *
 * <p>平台侧只看**聚合**：多少家、什么套餐、用多少量。客户档案、会话内容、工单明细
 * 这些属于租户自己的数据，不在这里出现——这是多租户产品里"平台能看什么"的边界。</p>
 *
 * <p>数据来源就一张 tenant_db：{@code tenant}（租户与套餐）+ {@code enterprise}（企业名）
 * + {@code tenant_usage_daily}（网关按天上报的用量）。真正"进账多少"在计费库，
 * 由 billing-service 的平台分析接口给，页面把两边拼起来。</p>
 */
@Service
public class PlatformAnalyticsService {

    /**
     * 免费版套餐 ID：计费库初始化脚本里固定写死的（billing_db_billing.sql，plan_code=FREE）。
     *
     * <p>放成常量而不是查库：tenant-service 不跨库读计费表，"什么算付费"这条口径
     * 写在这里、写清楚，比偷偷 join 别人的库可维护。</p>
     */
    private static final long FREE_PLAN_ID = 940000000000000000L;

    /** 增长趋势默认看几个月（原型是 8 个月） */
    private static final int DEFAULT_MONTHS = 8;

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final PlatformAnalyticsMapper analyticsMapper;

    public PlatformAnalyticsService(PlatformAnalyticsMapper analyticsMapper) {
        this.analyticsMapper = analyticsMapper;
    }

    /** 平台数据页要的全部聚合：总览 + 趋势 + 分布 + Top + 租户列表 */
    public PlatformAnalyticsVO analytics(int months, int page, int pageSize, String keyword, Integer status) {
        int span = months < 1 ? DEFAULT_MONTHS : Math.min(months, 24);
        int current = Math.max(1, page);
        int size = Math.min(Math.max(1, pageSize), 100);
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return new PlatformAnalyticsVO(overview(), growth(span), planDistribution(),
                statusDistribution(), topTenants(6), tenantPage(current, size, kw, status));
    }

    /** 顶部卡片：租户规模 + 本月用量 */
    private AnalyticsOverviewVO overview() {
        Map<String, Object> raw = analyticsMapper.selectTenantOverview(FREE_PLAN_ID);
        Map<String, Object> usage = analyticsMapper.selectMonthUsage();
        return new AnalyticsOverviewVO(intValue(value(raw, "total")), intValue(value(raw, "active")),
                intValue(value(raw, "frozen")), intValue(value(raw, "expired")),
                intValue(value(raw, "cancelled")), intValue(value(raw, "isolated")),
                intValue(value(raw, "paid")), intValue(value(raw, "new_this_month")),
                intValue(value(usage, "requests")), intValue(value(usage, "rejected")),
                intValue(value(usage, "sessions")), intValue(value(usage, "ai_calls")),
                YearMonth.now().toString());
    }

    /**
     * 增长趋势：把数据库返回的"有新增的月份"补成连续 N 个月，并算出累计租户数。
     *
     * <p>空月份补 0 而不是跳过：折线图上一个断点会被误读成"数据丢了"，
     * 主动补零才是"这个月确实没人注册"。</p>
     */
    private List<GrowthPointVO> growth(int months) {
        List<Map<String, Object>> rows = analyticsMapper.selectTenantGrowth(months);
        Map<String, Long> byMonth = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byMonth.put(String.valueOf(value(row, "month")), longValue(value(row, "new_tenants")));
        }
        // 累计 = 窗口之前已有的租户 + 每月新增
        long windowNew = byMonth.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> raw = analyticsMapper.selectTenantOverview(FREE_PLAN_ID);
        long total = longValue(value(raw, "total"));
        long cumulative = Math.max(0, total - windowNew);

        List<GrowthPointVO> result = new ArrayList<>();
        YearMonth cursor = YearMonth.now().minusMonths(months - 1L);
        for (int index = 0; index < months; index++) {
            String key = cursor.format(MONTH_FORMAT);
            long added = byMonth.getOrDefault(key, 0L);
            cumulative += added;
            result.add(new GrowthPointVO(key, (int) added, (int) cumulative));
            cursor = cursor.plusMonths(1);
        }
        return result;
    }

    private List<PlanDistributionVO> planDistribution() {
        List<PlanDistributionVO> result = new ArrayList<>();
        for (Map<String, Object> row : analyticsMapper.selectPlanDistribution()) {
            Object planId = value(row, "plan_id");
            result.add(new PlanDistributionVO(planId == null ? null : String.valueOf(planId),
                    intValue(value(row, "tenant_count"))));
        }
        return result;
    }

    private List<StatusDistributionVO> statusDistribution() {
        List<StatusDistributionVO> result = new ArrayList<>();
        for (Map<String, Object> row : analyticsMapper.selectStatusDistribution()) {
            int status = (int) longValue(value(row, "status"));
            result.add(new StatusDistributionVO(status, statusText(status),
                    intValue(value(row, "tenant_count"))));
        }
        return result;
    }

    private List<TopTenantVO> topTenants(int limit) {
        List<TopTenantVO> result = new ArrayList<>();
        for (Map<String, Object> row : analyticsMapper.selectTopTenants(limit)) {
            int status = (int) longValue(value(row, "status"));
            Object planId = value(row, "plan_id");
            result.add(new TopTenantVO(str(value(row, "tenant_code")),
                    str(value(row, "company_name")), status, statusText(status),
                    planId == null ? null : String.valueOf(planId),
                    intValue(value(row, "month_requests")),
                    intValue(value(row, "month_sessions"))));
        }
        return result;
    }

    private AnalyticsTenantPageVO tenantPage(int page, int size, String keyword, Integer status) {
        long total = analyticsMapper.countTenantPage(keyword, status);
        List<Map<String, Object>> rows = analyticsMapper.selectTenantPage(size, (long) (page - 1) * size,
                keyword, status);
        List<AnalyticsTenantRowVO> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            int tenantStatus = (int) longValue(value(row, "status"));
            Object planId = value(row, "plan_id");
            list.add(new AnalyticsTenantRowVO(str(value(row, "tenant_code")),
                    str(value(row, "company_name")), str(value(row, "industry")),
                    planId == null ? null : String.valueOf(planId), tenantStatus,
                    statusText(tenantStatus), text(value(row, "expire_time")),
                    text(value(row, "create_time")), intValue(value(row, "month_requests")),
                    intValue(value(row, "month_sessions")),
                    value(row, "last_active_date") == null
                            ? null : String.valueOf(value(row, "last_active_date"))));
        }
        return new AnalyticsTenantPageVO((int) total, page, size, list);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 与租户管理页同一套文案（1-正常、2-冻结、3-到期、4-注销） */
    private static String statusText(int status) {
        return switch (status) {
            case 1 -> "正常";
            case 2 -> "冻结";
            case 3 -> "到期";
            case 4 -> "注销";
            default -> "状态 " + status;
        };
    }

    private static Object value(Map<String, Object> row, String key) {
        return row == null ? null : row.get(key);
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
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 计数一律走 Integer：supportflow-common 的 Long→String 序列化会把小数变成字符串，
     * 前端拿去做除法/百分比就成了字符串拼接（实测把 50% 显示成 9%）。
     */
    private static int intValue(Object raw) {
        return (int) longValue(raw);
    }

    private static String text(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw);
        return value.length() >= 19 ? value.replace('T', ' ').substring(0, 19) : value;
    }

    /** 页面顶部提示用：当前统计口径 */
    public static String monthLabel() {
        return YearMonth.now() + "（" + LocalDate.now() + " 为止）";
    }
}
