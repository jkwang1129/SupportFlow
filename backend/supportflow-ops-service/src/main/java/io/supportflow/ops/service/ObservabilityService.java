package io.supportflow.ops.service;

import io.supportflow.common.audit.AuditLogClient;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.ops.dto.AiTraceVO;
import io.supportflow.ops.dto.AiUsageMetricsVO;
import io.supportflow.ops.dto.AiUsageOverviewVO;
import io.supportflow.ops.dto.AlertEventPageVO;
import io.supportflow.ops.dto.AlertEventVO;
import io.supportflow.ops.dto.AlertHandleVO;
import io.supportflow.ops.dto.AlertRuleListVO;
import io.supportflow.ops.dto.AlertRuleVO;
import io.supportflow.ops.dto.AlertSummaryVO;
import io.supportflow.ops.dto.BudgetBody;
import io.supportflow.ops.dto.BudgetListVO;
import io.supportflow.ops.dto.BudgetVO;
import io.supportflow.ops.dto.CostGuardVO;
import io.supportflow.ops.dto.OptionVO;
import io.supportflow.ops.dto.RuleBody;
import io.supportflow.ops.entity.ObservabilityAlertEvent;
import io.supportflow.ops.entity.ObservabilityAlertRule;
import io.supportflow.ops.entity.TenantCostBudget;
import io.supportflow.ops.internal.AiMetricsClient;
import io.supportflow.ops.internal.AiObservabilityClient;
import io.supportflow.ops.mapper.ObservabilityAlertEventMapper;
import io.supportflow.ops.mapper.ObservabilityAlertRuleMapper;
import io.supportflow.ops.mapper.TenantCostBudgetMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 平台「可观测」：告警规则 + 告警事件 + 成本预算 + 成本闸门 + 调用链查询。
 *
 * <p>四件事的关系：**指标**来自 AI 服务的计量（ai_db），**规则**决定什么算异常，
 * 命中就落**事件**（可确认/忽略），而**预算**是经营阀门——超了按配置的动作
 * 在业务链路里真的降级或拦下来（执行点在 customer-service 调 AI 之前）。</p>
 *
 * <p>两条硬约定：</p>
 * <ol>
 *   <li><b>事件不可改</b>：只能确认或忽略（并写说明），数值是当时的事实；</li>
 *   <li><b>告警不能刷屏</b>：同规则 + 同租户 + 同一天只落一条（唯一索引兜底），
 *       否则每 5 分钟扫描一次就是每 5 分钟一条。</li>
 * </ol>
 */
@Service
public class ObservabilityService {

    /** 审计模块名（与平台页筛选器一致） */
    private static final String MODULE = "可观测";

    /** 成本预算产生的事件用这个"伪规则号"：它不是某条规则，而是预算阀门 */
    public static final long BUDGET_RULE_ID = 0L;
    public static final String BUDGET_RULE_NAME = "成本预算（AI 成本治理）";

    public static final int METRIC_FAIL_RATE = 1;
    public static final int METRIC_LATENCY = 2;
    public static final int METRIC_COST = 3;
    public static final int METRIC_CALLS = 4;
    public static final int METRIC_TOKENS = 5;

    public static final int SCOPE_PLATFORM = 1;
    public static final int SCOPE_TENANT = 2;

    /** 超限动作：1-仅告警、2-降级、3-暂停 */
    public static final int ACTION_ALERT_ONLY = 1;
    public static final int ACTION_DEGRADE = 2;
    public static final int ACTION_BLOCK = 3;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Logger log = LoggerFactory.getLogger(ObservabilityService.class);

    private final ObservabilityAlertRuleMapper ruleMapper;
    private final ObservabilityAlertEventMapper eventMapper;
    private final TenantCostBudgetMapper budgetMapper;
    private final AiObservabilityClient aiClient;
    private final SnowflakeIdGenerator idGenerator;
    private final AuditLogClient auditLogClient;

    public ObservabilityService(ObservabilityAlertRuleMapper ruleMapper,
                                ObservabilityAlertEventMapper eventMapper,
                                TenantCostBudgetMapper budgetMapper,
                                AiObservabilityClient aiClient,
                                SnowflakeIdGenerator idGenerator,
                                AuditLogClient auditLogClient) {
        this.ruleMapper = ruleMapper;
        this.eventMapper = eventMapper;
        this.budgetMapper = budgetMapper;
        this.aiClient = aiClient;
        this.idGenerator = idGenerator;
        this.auditLogClient = auditLogClient;
    }

    // ------------------------------------------------------------------ 告警规则

    public AlertRuleListVO rules() {
        List<ObservabilityAlertRule> rows = ruleMapper.selectList(Wrappers.<ObservabilityAlertRule>lambdaQuery()
                .eq(ObservabilityAlertRule::getDeleted, false)
                .orderByAsc(ObservabilityAlertRule::getMetric)
                .orderByAsc(ObservabilityAlertRule::getId));
        List<AlertRuleVO> items = new ArrayList<>(rows.size());
        for (ObservabilityAlertRule row : rows) {
            items.add(ruleVO(row));
        }
        return new AlertRuleListVO(items, metricOptions());
    }

    @Transactional
    public AlertRuleListVO saveRule(LoginUser operator, RuleBody body, Long id) {
        if (body == null || body.ruleName() == null || body.ruleName().isBlank()) {
            throw new BizException(40001, "请填写规则名称");
        }
        int metric = clamp(body.metric() == null ? METRIC_FAIL_RATE : body.metric(),
                METRIC_FAIL_RATE, METRIC_TOKENS);
        int windowDays = clamp(body.windowDays() == null ? 1 : body.windowDays(), 1, 90);
        int compareOp = clamp(body.compareOp() == null ? 1 : body.compareOp(), 1, 2);
        int level = clamp(body.level() == null ? 2 : body.level(), 1, 3);
        int scope = clamp(body.scope() == null ? SCOPE_PLATFORM : body.scope(), SCOPE_PLATFORM, SCOPE_TENANT);
        BigDecimal threshold = body.threshold() == null ? BigDecimal.ZERO : body.threshold();
        if (threshold.signum() < 0) {
            throw new BizException(40001, "阈值不能是负数");
        }
        LocalDateTime now = LocalDateTime.now();
        String operatorName = operator == null ? "系统" : operator.name();
        boolean enabled = !Boolean.FALSE.equals(body.isEnabled());
        boolean notifyTenant = !Boolean.FALSE.equals(body.notifyTenant());
        ObservabilityAlertRule existing = id == null ? null : ruleMapper.selectById(id);
        if (existing == null) {
            ruleMapper.insert(ObservabilityAlertRule.builder()
                    .id(idGenerator.nextId())
                    .ruleName(body.ruleName().trim())
                    .metric((short) metric)
                    .windowDays((short) windowDays)
                    .compareOp((short) compareOp)
                    .threshold(threshold)
                    .level((short) level)
                    .scope((short) scope)
                    .notifyTenant(notifyTenant)
                    .isEnabled(enabled)
                    .remark(body.remark())
                    .createTime(now)
                    .updateTime(now)
                    .creator(operatorName)
                    .editor(operatorName)
                    .deleted(false)
                    .build());
        } else {
            ObservabilityAlertRule update = new ObservabilityAlertRule();
            update.setId(existing.getId());
            update.setRuleName(body.ruleName().trim());
            update.setMetric((short) metric);
            update.setWindowDays((short) windowDays);
            update.setCompareOp((short) compareOp);
            update.setThreshold(threshold);
            update.setLevel((short) level);
            update.setScope((short) scope);
            update.setNotifyTenant(notifyTenant);
            update.setIsEnabled(enabled);
            update.setRemark(body.remark());
            update.setUpdateTime(now);
            update.setEditor(operatorName);
            ruleMapper.updateById(update);
        }
        auditLogClient.record(operator, MODULE, existing == null ? "新建告警规则" : "修改告警规则",
                "告警规则", String.valueOf(id), true,
                "规则「" + body.ruleName().trim() + "」：" + metricName((short) metric)
                        + " " + compareText((short) compareOp) + " " + threshold
                        + "（窗口 " + windowDays + " 天，"
                        + (scope == SCOPE_TENANT ? "按租户" : "全平台") + "，启用=" + enabled + "）");
        return rules();
    }

    @Transactional
    public AlertRuleListVO deleteRule(LoginUser operator, long id) {
        ObservabilityAlertRule existing = ruleMapper.selectById(id);
        if (existing == null || Boolean.TRUE.equals(existing.getDeleted())) {
            throw new BizException(40401, "规则不存在：" + id);
        }
        ObservabilityAlertRule update = new ObservabilityAlertRule();
        update.setId(id);
        update.setDeleted(true);
        update.setUpdateTime(LocalDateTime.now());
        update.setEditor(operator == null ? "系统" : operator.name());
        ruleMapper.updateById(update);
        auditLogClient.record(operator, MODULE, "删除告警规则", "告警规则", String.valueOf(id), true,
                "删除规则「" + existing.getRuleName() + "」");
        return rules();
    }

    // ------------------------------------------------------------------ 告警事件

    public AlertEventPageVO events(Integer handleStatus, Integer level, String tenantCode,
                                   int page, int pageSize) {
        int current = Math.max(1, page);
        int size = Math.min(Math.max(1, pageSize), 200);
        Integer status = handleStatus == null || handleStatus <= 0 ? null : handleStatus;
        Integer lv = level == null || level <= 0 ? null : level;
        String tenant = tenantCode == null || tenantCode.isBlank() ? null : tenantCode.trim();
        long total = eventMapper.countEvents(status, lv, tenant);
        List<Map<String, Object>> list = eventMapper.selectEvents(status, lv, tenant,
                size, (long) (current - 1) * size);
        List<AlertEventVO> items = new ArrayList<>(list.size());
        for (Map<String, Object> row : list) {
            int metric = intOf(row.get("metric"));
            int eventLevel = intOf(row.get("level"));
            items.add(new AlertEventVO(stringOf(row.get("id")), stringOf(row.get("ruleId")),
                    stringOf(row.get("ruleName")), (short) metric, (short) eventLevel,
                    stringOf(row.get("tenantCode")), decimalOrNull(row.get("metricValue")),
                    decimalOrNull(row.get("threshold")),
                    row.get("windowDays") instanceof Number w ? w.shortValue() : null,
                    stringOf(row.get("statDate")), stringOf(row.get("detail")),
                    row.get("handleStatus") instanceof Number h ? h.shortValue() : null,
                    stringOf(row.get("handleRemark")), stringOf(row.get("handlerName")),
                    stringOf(row.get("handleTime")),
                    row.get("notified") instanceof Boolean b ? b : null,
                    stringOf(row.get("eventTime")), metricName((short) metric),
                    levelText((short) eventLevel), handleStatusText(intOf(row.get("handleStatus")))));
        }
        Map<String, Object> raw = eventMapper.selectOverview();
        AlertSummaryVO summary = new AlertSummaryVO(intOf(raw == null ? null : raw.get("total")),
                intOf(raw == null ? null : raw.get("pending")),
                intOf(raw == null ? null : raw.get("critical")),
                intOf(raw == null ? null : raw.get("warning")),
                intOf(raw == null ? null : raw.get("today")));
        return new AlertEventPageVO((int) total, current, size, items, summary);
    }

    /** 待处理条数：菜单角标用（与事件页同一口径） */
    public int pendingCount() {
        Map<String, Object> raw = eventMapper.selectOverview();
        return intOf(raw == null ? null : raw.get("pending"));
    }

    @Transactional
    public AlertHandleVO handle(LoginUser operator, long id, int status, String remark) {
        if (status != 2 && status != 3) {
            throw new BizException(40001, "只支持标记已确认（2）或已忽略（3）");
        }
        ObservabilityAlertEvent existing = eventMapper.selectById(id);
        if (existing == null) {
            throw new BizException(40401, "告警事件不存在：" + id);
        }
        eventMapper.handle(id, status, remark, operator == null ? "系统" : operator.name());
        auditLogClient.record(operator, MODULE, status == 2 ? "确认告警" : "忽略告警",
                "告警事件", String.valueOf(id), true,
                (status == 2 ? "确认" : "忽略") + "告警「" + existing.getRuleName() + "」"
                        + (existing.getTenantCode() == null ? "（全平台）" : "（" + existing.getTenantCode() + "）")
                        + (remark == null || remark.isBlank() ? "" : "，说明：" + remark));
        return new AlertHandleVO(String.valueOf(id), status);
    }

    // ------------------------------------------------------------------ 成本预算

    public BudgetListVO budgets() {
        List<TenantCostBudget> rows = budgetMapper.selectList(Wrappers.<TenantCostBudget>lambdaQuery()
                .eq(TenantCostBudget::getDeleted, false)
                .orderByAsc(TenantCostBudget::getTenantCode));
        List<BudgetVO> items = new ArrayList<>(rows.size());
        for (TenantCostBudget row : rows) {
            items.add(budgetVO(row));
        }
        return new BudgetListVO(items, actionOptions());
    }

    @Transactional
    public BudgetListVO saveBudget(LoginUser operator, BudgetBody body) {
        if (body == null || body.tenantCode() == null || body.tenantCode().isBlank()) {
            throw new BizException(40001, "请选择租户");
        }
        BigDecimal monthly = body.monthlyBudget() == null ? BigDecimal.ZERO : body.monthlyBudget();
        if (monthly.signum() < 0) {
            throw new BizException(40001, "预算不能是负数（0 表示不限）");
        }
        int ratio = clamp(body.alertRatio() == null ? 80 : body.alertRatio(), 1, 100);
        int action = clamp(body.overAction() == null ? ACTION_ALERT_ONLY : body.overAction(),
                ACTION_ALERT_ONLY, ACTION_BLOCK);
        boolean enabled = !Boolean.FALSE.equals(body.isEnabled());
        String tenant = body.tenantCode().trim();
        LocalDateTime now = LocalDateTime.now();
        String operatorName = operator == null ? "系统" : operator.name();
        TenantCostBudget existing = budgetMapper.selectOne(Wrappers.<TenantCostBudget>lambdaQuery()
                .eq(TenantCostBudget::getTenantCode, tenant)
                .eq(TenantCostBudget::getDeleted, false)
                .last("LIMIT 1"));
        if (existing == null) {
            budgetMapper.insert(TenantCostBudget.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenant)
                    .monthlyBudget(monthly)
                    .alertRatio((short) ratio)
                    .overAction((short) action)
                    .isEnabled(enabled)
                    .remark(body.remark())
                    .createTime(now)
                    .updateTime(now)
                    .creator(operatorName)
                    .editor(operatorName)
                    .deleted(false)
                    .build());
        } else {
            TenantCostBudget update = new TenantCostBudget();
            update.setId(existing.getId());
            update.setMonthlyBudget(monthly);
            update.setAlertRatio((short) ratio);
            update.setOverAction((short) action);
            update.setIsEnabled(enabled);
            update.setRemark(body.remark());
            update.setUpdateTime(now);
            update.setEditor(operatorName);
            budgetMapper.updateById(update);
        }
        auditLogClient.record(operator, MODULE, "保存成本预算", "租户", tenant, true,
                "租户 " + tenant + " 月预算 " + monthly + " 元，"
                        + ratio + "% 预警，超限动作=" + actionText((short) action)
                        + "，启用=" + enabled);
        return budgets();
    }

    /** Reads the current budget and month-to-date usage. Empty means enforcement could not be verified. */
    public Optional<CostGuardVO> costGuard(String tenant) {
        TenantCostBudget budget = budgetMapper.selectOne(Wrappers.<TenantCostBudget>lambdaQuery()
                .eq(TenantCostBudget::getTenantCode, tenant)
                .eq(TenantCostBudget::getDeleted, false)
                .last("LIMIT 1"));
        if (budget == null || !Boolean.TRUE.equals(budget.getIsEnabled())
                || budget.getMonthlyBudget() == null || budget.getMonthlyBudget().signum() <= 0) {
            return Optional.of(guardResult(ACTION_ALERT_ONLY, budget, BigDecimal.ZERO, BigDecimal.ZERO,
                    "未配置生效的成本预算，不限制"));
        }
        return aiClient.tenantCostForGuard(tenant, LocalDate.now().getDayOfMonth())
                .map(used -> guardDecision(budget, used));
    }

    private CostGuardVO guardDecision(TenantCostBudget budget, BigDecimal used) {
        BigDecimal limit = budget.getMonthlyBudget();
        BigDecimal ratio = used.multiply(BigDecimal.valueOf(100)).divide(limit, 1, RoundingMode.HALF_UP);
        int mode = ACTION_ALERT_ONLY;
        String reason = "预算内";
        if (used.compareTo(limit) >= 0) {
            mode = budget.getOverAction() == null ? ACTION_ALERT_ONLY : budget.getOverAction();
            String action = switch (mode) {
                case ACTION_ALERT_ONLY -> "按策略仅告警";
                case ACTION_BLOCK -> "按策略暂停 AI 能力";
                default -> "按策略降级为规则与检索";
            };
            reason = "本月 AI 成本已达到预算（" + used + " / " + limit + " 元），" + action;
        } else if (ratio.compareTo(BigDecimal.valueOf(budget.getAlertRatio() == null ? 80
                : budget.getAlertRatio())) >= 0) {
            reason = "本月 AI 成本已用 " + ratio + "%（" + used + " / " + limit + " 元）";
        }
        return guardResult(mode, budget, used, ratio, reason);
    }

    private CostGuardVO guardResult(int mode, TenantCostBudget budget,
                                    BigDecimal used, BigDecimal ratio, String reason) {
        return new CostGuardVO(mode, modeText(mode), mode != ACTION_BLOCK,
                mode == ACTION_DEGRADE, used,
                budget == null || budget.getMonthlyBudget() == null
                        ? BigDecimal.ZERO : budget.getMonthlyBudget(),
                ratio, budget != null, reason);
    }

    // ------------------------------------------------------------------ 调用链

    /** 按调用链号查这次请求在 AI 侧发生了什么（AI 服务出数，这里只做鉴权与转发） */
    public AiTraceVO trace(String traceId) {
        String key = traceId == null ? "" : traceId.trim();
        if (key.isEmpty()) {
            throw new BizException(40001, "请填写调用链号");
        }
        AiTraceVO trace = aiClient.trace(key);
        // requestId 用查询用的那个号覆盖：AI 侧没查到时会回自己的兜底值
        return new AiTraceVO(trace.found(), trace.items(), key);
    }

    // ------------------------------------------------------------------ 评估（供定时任务调用）

    /**
     * 跑一轮评估：全平台规则 + 按租户规则 + 成本预算。
     *
     * @return 本轮新建的事件数
     */
    @Transactional
    public int evaluate(AiMetricsClient metricsClient) {
        List<ObservabilityAlertRule> rules = ruleMapper.selectList(Wrappers.<ObservabilityAlertRule>lambdaQuery()
                .eq(ObservabilityAlertRule::getDeleted, false)
                .eq(ObservabilityAlertRule::getIsEnabled, true));
        List<TenantCostBudget> budgets = budgetMapper.selectList(Wrappers.<TenantCostBudget>lambdaQuery()
                .eq(TenantCostBudget::getDeleted, false)
                .eq(TenantCostBudget::getIsEnabled, true));
        if (rules.isEmpty() && budgets.isEmpty()) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        int created = 0;
        // ① 全平台规则：一次大盘查询（按窗口最大天数取一遍，各规则各取所需）
        Map<Integer, AiUsageMetricsVO> platformMetrics = new LinkedHashMap<>();
        for (ObservabilityAlertRule rule : rules) {
            if (rule.getScope() == null || rule.getScope() != SCOPE_TENANT) {
                int days = rule.getWindowDays() == null ? 1 : rule.getWindowDays();
                AiUsageMetricsVO metrics = platformMetrics.computeIfAbsent(days,
                        key -> metricsClient.metrics(key));
                BigDecimal value = metricValue(metrics, rule.getMetric());
                if (hit(value, rule)) {
                    created += createEventIfAbsent(rule, null, value, today,
                            metricText(rule.getMetric()) + " " + value + "（阈值 "
                                    + compareText(rule.getCompareOp()) + " " + rule.getThreshold() + "，窗口 "
                                    + days + " 天）");
                }
            }
        }
        // ② 按租户规则：一条 GROUP BY 出全量，再逐个比较（不能 N 个租户发 N 次查询）
        List<ObservabilityAlertRule> tenantRules = new ArrayList<>();
        for (ObservabilityAlertRule rule : rules) {
            if (rule.getScope() != null && rule.getScope() == SCOPE_TENANT) {
                tenantRules.add(rule);
            }
        }
        if (!tenantRules.isEmpty()) {
            Map<Integer, List<Map<String, Object>>> byWindow = new LinkedHashMap<>();
            for (ObservabilityAlertRule rule : tenantRules) {
                int days = rule.getWindowDays() == null ? 1 : rule.getWindowDays();
                List<Map<String, Object>> rows = byWindow.computeIfAbsent(days, aiClient::tenantMetrics);
                for (Map<String, Object> row : rows) {
                    String tenant = row.get("tenantCode") == null ? null : String.valueOf(row.get("tenantCode"));
                    BigDecimal value = metricValue(row, rule.getMetric());
                    if (tenant != null && hit(value, rule)) {
                        created += createEventIfAbsent(rule, tenant, value, today,
                                metricText(rule.getMetric()) + " " + value + "（阈值 "
                                        + compareText(rule.getCompareOp()) + " " + rule.getThreshold()
                                        + "，窗口 " + days + " 天）");
                    }
                }
            }
        }
        // ③ 成本预算：达到预警线 / 超限各落一条（同一天同一租户只留一条，detail 反映当时状态）
        for (TenantCostBudget budget : budgets) {
            if (budget.getMonthlyBudget() == null || budget.getMonthlyBudget().signum() <= 0) {
                continue;
            }
            String tenant = budget.getTenantCode();
            Map<String, Object> usage = aiClient.tenantTotal(tenant, today.getDayOfMonth());
            BigDecimal used = decimalOf(usage.get("cost"));
            BigDecimal ratio = used.multiply(BigDecimal.valueOf(100))
                    .divide(budget.getMonthlyBudget(), 1, RoundingMode.HALF_UP);
            int alertRatio = budget.getAlertRatio() == null ? 80 : budget.getAlertRatio();
            if (ratio.compareTo(BigDecimal.valueOf(alertRatio)) < 0) {
                continue;
            }
            boolean over = ratio.compareTo(BigDecimal.valueOf(100)) >= 0;
            ObservabilityAlertRule pseudo = ObservabilityAlertRule.builder()
                    .id(BUDGET_RULE_ID)
                    .ruleName(BUDGET_RULE_NAME)
                    .metric((short) METRIC_COST)
                    .windowDays((short) today.getDayOfMonth())
                    .compareOp((short) 1)
                    .threshold(budget.getMonthlyBudget())
                    .level((short) (over ? 3 : 2))
                    .scope((short) SCOPE_TENANT)
                    .notifyTenant(true)
                    .build();
            created += createEventIfAbsent(pseudo, tenant, used, today,
                    "本月 AI 成本已用 " + ratio + "%（" + used + " / " + budget.getMonthlyBudget()
                            + " 元）" + (over ? "，超限动作：" + actionText(budget.getOverAction()) : ""));
        }
        return created;
    }

    /**
     * 幂等地落一条事件：同规则 + 同租户 + 同一天只留一条。
     *
     * <p>判断交给数据库的 `ON CONFLICT DO NOTHING`（返回 0 行 = 已存在）。
     * **不要**改成「先查再插 + catch 重复键」：PostgreSQL 里一条语句违反约束会让
     * 整个事务 aborted，catch 之后同一事务里的后续语句全部报错——
     * 定时评估与手动评估撞在一起时就会踩到。</p>
     */
    private int createEventIfAbsent(ObservabilityAlertRule rule, String tenantCode,
                                    BigDecimal value, LocalDate statDate, String detail) {
        LocalDateTime now = LocalDateTime.now();
        int inserted = eventMapper.insertIgnore(ObservabilityAlertEvent.builder()
                .id(idGenerator.nextId())
                .ruleId(rule.getId())
                .ruleName(rule.getRuleName())
                .metric(rule.getMetric())
                .level(rule.getLevel())
                .tenantCode(tenantCode)
                .metricValue(value)
                .threshold(rule.getThreshold())
                .windowDays(rule.getWindowDays())
                .statDate(statDate)
                .detail(detail)
                .handleStatus((short) 1)
                .notified(false)
                .eventTime(now)
                .createTime(now)
                .updateTime(now)
                .build());
        if (inserted == 0) {
            // 已存在：同一轮或上一轮的评估已经落下过这条，静默跳过就好
            return 0;
        }
        log.info("告警命中 rule={} tenant={} value={} threshold={}",
                rule.getRuleName(), tenantCode, value, rule.getThreshold());
        return 1;
    }

    /** 标记事件已通知企业（幂等，重复通知会打扰同一个客户） */
    public void markNotified(long eventId) {
        eventMapper.markNotified(eventId);
    }

    // ------------------------------------------------------------------ 小工具

    private static boolean hit(BigDecimal value, ObservabilityAlertRule rule) {
        if (value == null || rule.getThreshold() == null) {
            return false;
        }
        int op = rule.getCompareOp() == null ? 1 : rule.getCompareOp();
        return op == 2 ? value.compareTo(rule.getThreshold()) <= 0
                : value.compareTo(rule.getThreshold()) >= 0;
    }

    /** 从大盘 / 租户行里取某个指标的值（口径写在一处，避免各处自己算） */
    private static BigDecimal metricValue(Map<String, Object> metrics, Short metric) {
        if (metrics == null || metrics.isEmpty()) {
            return null;
        }
        Map<String, Object> source = metrics;
        Object overview = metrics.get("overview");
        if (overview instanceof Map<?, ?> map) {
            source = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                source.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        int code = metric == null ? METRIC_FAIL_RATE : metric;
        return switch (code) {
            case METRIC_FAIL_RATE -> {
                BigDecimal calls = decimalOf(source.get("calls"));
                BigDecimal failed = decimalOf(source.get("failed_calls"));
                if (calls.signum() <= 0) {
                    yield BigDecimal.ZERO;
                }
                yield failed.multiply(BigDecimal.valueOf(100))
                        .divide(calls, 2, RoundingMode.HALF_UP);
            }
            case METRIC_LATENCY -> decimalOf(source.get("avg_latency_ms"));
            case METRIC_COST -> decimalOf(source.get("cost"));
            case METRIC_CALLS -> decimalOf(source.get("calls"));
            case METRIC_TOKENS -> decimalOf(source.get("tokens") != null
                    ? source.get("tokens")
                    : sumTokens(source));
            default -> null;
        };
    }

    /**
     * 从 AI 用量大盘（实体）里取某个指标的值。
     *
     * <p>口径与下面那个 Map 版保持一致；那边留给"按租户指标"（Python 侧返回的行还是 Map）。</p>
     */
    private static BigDecimal metricValue(AiUsageMetricsVO metrics, Short metric) {
        if (metrics == null || metrics.overview() == null) {
            return null;
        }
        AiUsageOverviewVO overview = metrics.overview();
        int code = metric == null ? METRIC_FAIL_RATE : metric;
        return switch (code) {
            case METRIC_FAIL_RATE -> {
                BigDecimal calls = decimalOf(overview.calls());
                BigDecimal failed = decimalOf(overview.failedCalls());
                if (calls.signum() <= 0) {
                    yield BigDecimal.ZERO;
                }
                yield failed.multiply(BigDecimal.valueOf(100))
                        .divide(calls, 2, RoundingMode.HALF_UP);
            }
            case METRIC_LATENCY -> decimalOf(overview.avgLatencyMs());
            case METRIC_COST -> decimalOf(overview.cost());
            case METRIC_CALLS -> decimalOf(overview.calls());
            case METRIC_TOKENS -> decimalOf(overview.tokens() != null
                    ? overview.tokens()
                    : sumTokens(overview));
            default -> null;
        };
    }

    private static BigDecimal sumTokens(AiUsageOverviewVO overview) {
        return decimalOf(overview.inputTokens()).add(decimalOf(overview.outputTokens()));
    }

    private static BigDecimal sumTokens(Map<String, Object> source) {
        return decimalOf(source.get("input_tokens")).add(decimalOf(source.get("output_tokens")));
    }

    /**
     * 预算行：只回租户编号与本月的用量进度。
     *
     * <p>企业名不在这里拼：平台页面本来就有租户下拉（带企业名），
     * 让页面按编号显示名字，比让 ops 再连一次 tenant-service 拿一份名单划算。</p>
     */
    private BudgetVO budgetVO(TenantCostBudget row) {
        Map<String, Object> usage = aiClient.tenantTotal(row.getTenantCode(), LocalDate.now().getDayOfMonth());
        BigDecimal used = decimalOf(usage.get("cost"));
        BigDecimal budget = row.getMonthlyBudget() == null ? BigDecimal.ZERO : row.getMonthlyBudget();
        return new BudgetVO(String.valueOf(row.getId()), row.getTenantCode(), row.getMonthlyBudget(),
                row.getAlertRatio(), row.getOverAction(), actionText(row.getOverAction()),
                row.getIsEnabled(), row.getRemark(),
                row.getUpdateTime() == null ? null : row.getUpdateTime().format(TIME_FORMAT),
                used,
                budget.signum() <= 0 ? BigDecimal.ZERO
                        : used.multiply(BigDecimal.valueOf(100)).divide(budget, 1, RoundingMode.HALF_UP),
                !Boolean.FALSE.equals(usage.get("available")));
    }

    private static AlertRuleVO ruleVO(ObservabilityAlertRule row) {
        return new AlertRuleVO(String.valueOf(row.getId()), row.getRuleName(), row.getMetric(),
                metricName(row.getMetric()), row.getWindowDays(), row.getCompareOp(),
                compareText(row.getCompareOp()), row.getThreshold(), row.getLevel(),
                levelText(row.getLevel()), row.getScope(),
                row.getScope() != null && row.getScope() == SCOPE_TENANT ? "按租户" : "全平台",
                row.getNotifyTenant(), row.getIsEnabled(), row.getRemark(),
                row.getUpdateTime() == null ? null : row.getUpdateTime().format(TIME_FORMAT));
    }

    private static List<OptionVO> metricOptions() {
        List<OptionVO> options = new ArrayList<>();
        for (int code = METRIC_FAIL_RATE; code <= METRIC_TOKENS; code++) {
            options.add(new OptionVO(code, metricName((short) code)));
        }
        return options;
    }

    private static List<OptionVO> actionOptions() {
        List<OptionVO> options = new ArrayList<>();
        for (int code = ACTION_ALERT_ONLY; code <= ACTION_BLOCK; code++) {
            options.add(new OptionVO(code, actionText((short) code)));
        }
        return options;
    }

    public static String metricName(Short metric) {
        int code = metric == null ? 0 : metric;
        return switch (code) {
            case METRIC_FAIL_RATE -> "调用失败率（%）";
            case METRIC_LATENCY -> "平均耗时（ms）";
            case METRIC_COST -> "AI 成本（元）";
            case METRIC_CALLS -> "调用量（次）";
            case METRIC_TOKENS -> "Token 数";
            default -> "未知指标";
        };
    }

    private static String metricText(Short metric) {
        return metricName(metric);
    }

    public static String levelText(Short level) {
        int code = level == null ? 0 : level;
        return switch (code) {
            case 3 -> "严重";
            case 2 -> "警告";
            default -> "提示";
        };
    }

    public static String handleStatusText(int status) {
        return switch (status) {
            case 2 -> "已确认";
            case 3 -> "已忽略";
            default -> "待处理";
        };
    }

    public static String compareText(Short op) {
        return op != null && op == 2 ? "≤" : "≥";
    }

    public static String actionText(Short action) {
        int code = action == null ? 0 : action;
        return switch (code) {
            case ACTION_DEGRADE -> "降级到规则/检索";
            case ACTION_BLOCK -> "暂停 AI 能力";
            default -> "仅告警";
        };
    }

    public static String modeText(int mode) {
        return switch (mode) {
            case ACTION_DEGRADE -> "降级";
            case ACTION_BLOCK -> "暂停";
            default -> "正常";
        };
    }

    private static BigDecimal decimalOf(Object raw) {
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static int intOf(Object raw) {
        if (raw == null) {
            return 0;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String stringOf(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static BigDecimal decimalOrNull(Object raw) {
        return raw == null ? null : decimalOf(raw);
    }

}
