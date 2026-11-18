package io.supportflow.billing.service;

import io.supportflow.billing.dto.PayMethodRevenueVO;
import io.supportflow.billing.dto.PlanRevenueVO;
import io.supportflow.billing.dto.RevenueAnalyticsVO;
import io.supportflow.billing.dto.RevenueTrendVO;
import io.supportflow.billing.dto.StatementSummaryVO;
import io.supportflow.billing.mapper.PlatformRevenueMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台数据页的"钱"这一半：平台收入。
 *
 * <p>两套口径分开摆，不许混：</p>
 * <ol>
 *   <li><b>预付费实收</b>：订单（买套餐）已支付的金额——这才是平台真正收到的钱；</li>
 *   <li><b>后付费账期</b>：账期账单（超量费）的待结算 / 已结算 / 逾期，单独一组数字。</li>
 * </ol>
 *
 * <p>把两套混成一个"总收入"，就会出现"客户明明付过钱，页面显示已收 0"——
 * 第 28 篇下篇踩过这个坑，这里不再犯。</p>
 */
@Service
public class PlatformRevenueService {

    private static final Logger log = LoggerFactory.getLogger(PlatformRevenueService.class);

    /** 收入趋势默认看 6 个月 */
    private static final int DEFAULT_MONTHS = 6;

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final PlatformRevenueMapper revenueMapper;

    public PlatformRevenueService(PlatformRevenueMapper revenueMapper) {
        this.revenueMapper = revenueMapper;
    }

    public RevenueAnalyticsVO analytics(int months) {
        int span = months < 1 ? DEFAULT_MONTHS : Math.min(months, 24);
        String cycleKey = YearMonth.now().toString();

        Map<String, Object> raw = revenueMapper.selectRevenueOverview(cycleKey);
        long paidOrders = longValue(value(raw, "paid_orders"));
        BigDecimal paidTotal = decimal(raw, "paid_total");
        return new RevenueAnalyticsVO(cycleKey, paidTotal,
                decimal(raw, "paid_this_month"),
                paidOrders,
                longValue(value(raw, "paid_orders_this_month")),
                longValue(value(raw, "pending_orders")),
                decimal(raw, "pending_amount"),
                longValue(value(raw, "paying_tenants")),
                paidOrders == 0 ? BigDecimal.ZERO
                        : paidTotal.divide(BigDecimal.valueOf(paidOrders), 2, RoundingMode.HALF_UP),
                revenueTrend(span), planRevenue(), payMethodRevenue(), statements());
    }

    /** 近 N 个月实收趋势：空月份补 0（否则折线上一个断点会被误读成"数据丢了"） */
    private List<RevenueTrendVO> revenueTrend(int months) {
        Map<String, Map<String, Object>> byMonth = new LinkedHashMap<>();
        for (Map<String, Object> row : revenueMapper.selectRevenueTrend(months)) {
            byMonth.put(String.valueOf(value(row, "month")), row);
        }
        List<RevenueTrendVO> result = new ArrayList<>();
        YearMonth cursor = YearMonth.now().minusMonths(months - 1L);
        for (int index = 0; index < months; index++) {
            String key = cursor.format(MONTH_FORMAT);
            Map<String, Object> row = byMonth.get(key);
            result.add(new RevenueTrendVO(key, decimal(row, "paid_amount"),
                    longValue(value(row, "paid_orders"))));
            cursor = cursor.plusMonths(1);
        }
        return result;
    }

    private List<PlanRevenueVO> planRevenue() {
        List<PlanRevenueVO> result = new ArrayList<>();
        for (Map<String, Object> row : revenueMapper.selectPlanRevenue()) {
            result.add(new PlanRevenueVO(String.valueOf(value(row, "plan_id")),
                    stringValue(value(row, "plan_code")), stringValue(value(row, "plan_name")),
                    longValue(value(row, "paid_orders")), decimal(row, "paid_amount")));
        }
        return result;
    }

    private List<PayMethodRevenueVO> payMethodRevenue() {
        List<PayMethodRevenueVO> result = new ArrayList<>();
        for (Map<String, Object> row : revenueMapper.selectPayMethodRevenue()) {
            int method = (int) longValue(value(row, "pay_method"));
            result.add(new PayMethodRevenueVO(method, payMethodText(method),
                    longValue(value(row, "paid_orders")), decimal(row, "paid_amount")));
        }
        return result;
    }

    /** 账期账单：表是增量脚本建的，老库万一还没有，也别把整页统计带崩 */
    private StatementSummaryVO statements() {
        try {
            Map<String, Object> raw = revenueMapper.selectStatementSummary();
            return new StatementSummaryVO(longValue(value(raw, "statements")),
                    decimal(raw, "settled_amount"), decimal(raw, "pending_amount"),
                    decimal(raw, "overdue_amount"), longValue(value(raw, "overdue_count")));
        } catch (Exception e) {
            log.warn("账期账单汇总失败（表可能还没建），按 0 处理：{}", e.getMessage());
            return new StatementSummaryVO(0L, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, 0L);
        }
    }

    private static String stringValue(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    /** 与收款流水页同一套文案：1-支付宝、2-微信、3-对公转账 */
    private static String payMethodText(int method) {
        return switch (method) {
            case 1 -> "支付宝";
            case 2 -> "微信支付";
            case 3 -> "对公转账";
            default -> "其他";
        };
    }

    private static Object value(Map<String, Object> row, String key) {
        return row == null ? null : row.get(key);
    }

    private static long longValue(Object raw) {
        return UsageMeterService.longValue(raw);
    }

    private static BigDecimal decimal(Map<String, Object> row, String key) {
        return UsageMeterService.decimalValue(value(row, key));
    }
}
