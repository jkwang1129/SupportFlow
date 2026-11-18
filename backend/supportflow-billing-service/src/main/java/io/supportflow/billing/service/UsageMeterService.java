package io.supportflow.billing.service;

import io.supportflow.billing.dto.PlanInfo;
import io.supportflow.billing.dto.UsageCurrentVO;
import io.supportflow.billing.dto.UsageDailyVO;
import io.supportflow.billing.dto.UsageSnapshotVO;
import io.supportflow.billing.entity.Plan;
import io.supportflow.billing.internal.TenantPlanClient;
import io.supportflow.billing.mapper.BillingMapper;
import io.supportflow.billing.mapper.PlanMapper;
import io.supportflow.common.id.SnowflakeIdGenerator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用量计量：写入按天用量、按账期汇总、与套餐配额比对。
 *
 * <p>计量的口径（和页面上的"用量"完全一致）：</p>
 * <ul>
 *   <li><b>会话数</b>：客户进线开一次算一次（机器人 + 人工）；</li>
 *   <li><b>消息数</b>：客户 / 坐席 / 机器人发的消息总数；</li>
 *   <li><b>AI 调用</b>：客服大脑、质检、检索、视觉各算一次；</li>
 *   <li><b>坐席数与存储</b>：快照值（当天峰值），不进账单金额、只用来判断"是否超套餐"。</li>
 * </ul>
 */
@Service
public class UsageMeterService {

    private static final Logger log = LoggerFactory.getLogger(UsageMeterService.class);

    private final BillingMapper billingMapper;
    private final PlanMapper planMapper;
    private final TenantPlanClient tenantPlanClient;
    private final SnowflakeIdGenerator idGenerator;

    public UsageMeterService(BillingMapper billingMapper, PlanMapper planMapper,
                             TenantPlanClient tenantPlanClient,
                             SnowflakeIdGenerator idGenerator) {
        this.billingMapper = billingMapper;
        this.planMapper = planMapper;
        this.tenantPlanClient = tenantPlanClient;
        this.idGenerator = idGenerator;
    }

    /** 上报一条用量（幂等累加；同一天重复上报只是把数字加上去） */
    @Transactional
    public void report(String tenantCode, LocalDate statDate, long sessions, long messages,
                       long aiCalls, int seats, BigDecimal storageGb) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return;
        }
        billingMapper.upsertUsage(idGenerator.nextId(), tenantCode,
                statDate == null ? LocalDate.now() : statDate,
                sessions, messages, aiCalls, seats,
                storageGb == null ? BigDecimal.ZERO : storageGb);
        log.debug("用量上报 tenant={} date={} sessions={} messages={} aiCalls={}",
                tenantCode, statDate, sessions, messages, aiCalls);
    }

    /**
     * 当前账期用量 vs 套餐配额（企业侧"套餐与用量"卡片、平台侧详情都用它）。
     *
     * <p>`usagePercent` 是"最紧的那一项"的占用率：四项里只要有一项快满了，
     * 页面就该提示升级，而不是等某项超了才发现。</p>
     */
    public UsageCurrentVO currentUsage(String tenantCode, Long planId) {
        YearMonth cycle = YearMonth.now();
        Map<String, Object> sum = billingMapper.sumUsage(tenantCode, cycle.atDay(1), cycle.atEndOfMonth());
        long sessions = longValue(sum == null ? null : sum.get("sessions"));
        long messages = longValue(sum == null ? null : sum.get("messages"));
        long aiCalls = longValue(sum == null ? null : sum.get("ai_calls"));
        int seats = intValue(sum == null ? null : sum.get("seats"));
        BigDecimal storage = decimalValue(sum == null ? null : sum.get("storage_gb"));
        // 没传 planId 就去 tenant-service 问"这个租户现在是什么套餐"——
        // 权威值在 tenant_db 的 tenant.plan_id；购买生效后这里立刻能反映出来
        PlanInfo planInfo = planId == null
                ? tenantPlanClient.planOf(tenantCode) : null;
        Long effectivePlanId = planId != null ? planId : planInfo.planId();
        Plan plan = effectivePlanId == null ? null : planMapper.selectById(effectivePlanId);

        return new UsageCurrentVO(cycle.toString(), sessions, messages, aiCalls, seats, storage,
                plan == null ? null : plan.getId(),
                plan == null ? null : plan.getPlanName(),
                planInfo == null ? null : planInfo.expireTime(),
                plan == null ? 0 : plan.getQuotaRobotSessions(),
                plan == null ? 0 : plan.getQuotaAiCalls(),
                plan == null ? 0 : plan.getQuotaSeats(),
                plan == null ? 0 : plan.getQuotaStorageGb(),
                usagePercent(plan, sessions, aiCalls, seats, storage),
                dailyOf(billingMapper.selectUsageDaily(tenantCode, cycle.atDay(1), cycle.atEndOfMonth())));
    }

    /** 按天的用量明细（Map 行 → 实体） */
    private static List<UsageDailyVO> dailyOf(List<Map<String, Object>> rows) {
        List<UsageDailyVO> list = new ArrayList<>(rows == null ? 0 : rows.size());
        if (rows == null) {
            return list;
        }
        for (Map<String, Object> row : rows) {
            list.add(new UsageDailyVO(localDateValue(row.get("stat_date")),
                    longOrNull(row.get("sessions")), longOrNull(row.get("messages")),
                    longOrNull(row.get("ai_calls")), intOrNull(row.get("seats")),
                    decimalOrNull(row.get("storage_gb"))));
        }
        return list;
    }

    /** 用量快照（生成账单时存进 billing_statement.usage_snapshot，用于解释金额） */
    public UsageSnapshotVO snapshot(String tenantCode, YearMonth cycle) {
        Map<String, Object> sum = billingMapper.sumUsage(tenantCode, cycle.atDay(1), cycle.atEndOfMonth());
        return new UsageSnapshotVO(cycle.toString(),
                longValue(sum == null ? null : sum.get("sessions")),
                longValue(sum == null ? null : sum.get("messages")),
                longValue(sum == null ? null : sum.get("ai_calls")),
                intValue(sum == null ? null : sum.get("seats")),
                decimalValue(sum == null ? null : sum.get("storage_gb")));
    }

    /** 最紧的一项占用率（四舍五入到整数百分比） */
    private static int usagePercent(Plan plan, long sessions, long aiCalls, int seats, BigDecimal storage) {
        if (plan == null) {
            return 0;
        }
        List<Integer> percents = new ArrayList<>();
        percents.add(percent(sessions, plan.getQuotaRobotSessions()));
        percents.add(percent(aiCalls, plan.getQuotaAiCalls()));
        percents.add(percent(seats, plan.getQuotaSeats()));
        percents.add(percent(storage, plan.getQuotaStorageGb()));
        return percents.stream().filter(value -> value > 0).max(Integer::compareTo).orElse(0);
    }

    private static int percent(long used, Integer quota) {
        if (quota == null || quota <= 0) {
            return 0;
        }
        return (int) Math.min(999, Math.round(used * 100.0 / quota));
    }

    private static int percent(BigDecimal used, Integer quota) {
        if (quota == null || quota <= 0 || used == null) {
            return 0;
        }
        return (int) Math.min(999, Math.round(used.doubleValue() * 100.0 / quota));
    }

    static long longValue(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    static int intValue(Object value) {
        return (int) longValue(value);
    }

    static BigDecimal decimalValue(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    // ---------- 下面几个保留 null：明细列表里"没有值"和"值是 0"是两回事 ----------

    private static Long longOrNull(Object value) {
        return value == null ? null : longValue(value);
    }

    private static Integer intOrNull(Object value) {
        return value == null ? null : intValue(value);
    }

    private static BigDecimal decimalOrNull(Object value) {
        return value == null ? null : decimalValue(value);
    }

    /** JDBC 的 DATE 可能是 java.sql.Date / LocalDate / 字符串，统一成 LocalDate */
    private static LocalDate localDateValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof LocalDateTime time) {
            return time.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value).substring(0, 10));
    }
}
