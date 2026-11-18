package io.supportflow.billing.service;

import io.supportflow.billing.dto.BillingOrderPageVO;
import io.supportflow.billing.dto.BillingOverviewVO;
import io.supportflow.billing.dto.BillingStatementPageVO;
import io.supportflow.billing.dto.FreeTrialVO;
import io.supportflow.billing.dto.OrderCreatedVO;
import io.supportflow.billing.dto.OrderPaidVO;
import io.supportflow.billing.dto.PlanDistributionVO;
import io.supportflow.billing.dto.PlanInfo;
import io.supportflow.billing.dto.UsageSnapshotVO;
import io.supportflow.billing.entity.BillingStatement;
import io.supportflow.billing.entity.BillingOrder;
import io.supportflow.billing.entity.Plan;
import io.supportflow.billing.internal.TenantPlanClient;
import io.supportflow.billing.mapper.BillingMapper;
import io.supportflow.billing.mapper.BillingOrderMapper;
import io.supportflow.billing.mapper.BillingStatementMapper;
import io.supportflow.billing.mapper.PlanMapper;
import io.supportflow.common.api.ResultCode;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.audit.AuditLogClient;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.common.util.Texts;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 计费主链路：套餐 → 用量 → 账期账单 → 结算。
 *
 * <p>三处设计要点：</p>
 * <ol>
 *   <li><b>账单生成幂等</b>：`billing_statement` 上 (租户, 账期) 唯一，重复生成只是重算，
 *       不会多出两张账单；</li>
 *   <li><b>金额可解释</b>：套餐费 + 超量费分开存，同时把当时的用量快照写进
 *       `usage_snapshot`——半年后客户问"这 3000 块怎么来的"，能原样还原；</li>
 *   <li><b>超量只对"能计量的项"收钱</b>：会话数按每 1000 次单价计费（套餐里配），
 *       坐席/存储只用来提示升级，不产生费用——避免"存储涨了 1MB 也要算钱"这种扯不清的账。</li>
 * </ol>
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    /** 账单状态：1-待结算、2-已结算、3-已逾期、4-已作废 */
    public static final int STATEMENT_PENDING = 1;
    public static final int STATEMENT_SETTLED = 2;
    public static final int STATEMENT_OVERDUE = 3;

    /** 账期账单的默认结算期限（天） */
    private static final int DUE_DAYS = 15;

    /** 计费周期码（与 billing_order.cycle 的取值一致） */
    private static final int CYCLE_YEARLY = 1;
    private static final int CYCLE_MONTHLY = 2;

    /** 支付方式码（与 billing_order.pay_method 的取值一致） */
    private static final int PAY_METHOD_ALIPAY = 1;
    private static final int PAY_METHOD_WECHAT = 2;
    private static final int PAY_METHOD_CORP = 3;

    private final BillingMapper billingMapper;
    private final BillingStatementMapper statementMapper;
    private final BillingOrderMapper orderMapper;
    private final PlanMapper planMapper;
    private final UsageMeterService usageMeterService;
    private final TenantPlanClient tenantPlanClient;
    private final SnowflakeIdGenerator idGenerator;
    /** 审计日志：账单的生成 / 结算 / 作废都是钱相关动作，必须留痕 */
    private final AuditLogClient auditLogClient;
    /** 支付渠道开关：在线支付关掉之后，payOrder 必须拒绝对应渠道（执行点在这一层） */
    private final PlatformBillingSettingsService settingsService;
    /** 收款流水：订单支付 / 账期结算各写一条（对账页读的就是它） */
    private final PaymentRecordService paymentRecordService;

    /** 审计用的模块名（与平台页筛选器一致） */
    private static final String AUDIT_MODULE = "计费与账单";

    public BillingService(BillingMapper billingMapper,
                          BillingStatementMapper statementMapper,
                          BillingOrderMapper orderMapper,
                          PlanMapper planMapper,
                          UsageMeterService usageMeterService,
                          TenantPlanClient tenantPlanClient,
                          SnowflakeIdGenerator idGenerator,
                          AuditLogClient auditLogClient,
                          PlatformBillingSettingsService settingsService,
                          PaymentRecordService paymentRecordService) {
        this.billingMapper = billingMapper;
        this.statementMapper = statementMapper;
        this.orderMapper = orderMapper;
        this.planMapper = planMapper;
        this.usageMeterService = usageMeterService;
        this.tenantPlanClient = tenantPlanClient;
        this.idGenerator = idGenerator;
        this.auditLogClient = auditLogClient;
        this.settingsService = settingsService;
        this.paymentRecordService = paymentRecordService;
    }

    // ------------------------------------------------------------------ 套餐

    public List<Plan> plans() {
        return billingMapper.selectPlans();
    }

    /**
     * 下单：按套餐 + 周期算出金额并落库（状态=待支付）。
     *
     * <p>订单落库而不是"算完就返回"：支付回调必须能查到订单（金额以订单为准，
     * 不能用请求里的套餐现算——那是"用户说了算"的漏洞）。</p>
     */
    @Transactional
    public OrderCreatedVO createOrder(String tenantCode, long planId, int cycle) {
        Plan plan = planMapper.selectById(planId);
        if (plan == null || Boolean.FALSE.equals(plan.getIsEnabled())) {
            throw new BizException(40001, "套餐不存在或已停用");
        }
        if (cycle != 1 && cycle != 2) {
            throw new BizException(40001, "周期只能是 1-年付 / 2-月付");
        }
        BigDecimal amount = cycle == CYCLE_YEARLY ? plan.getPriceYearly() : plan.getPriceMonthly();
        String orderNo = nextOrderNo();
        LocalDateTime now = LocalDateTime.now();
        BillingOrder entity = BillingOrder.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenantCode)
                .orderNo(orderNo)
                .planId(plan.getId())
                .cycle(cycle)
                .amount(amount)
                .status(1)
                .invoiceStatus(1)
                .createTime(now)
                .updateTime(now)
                .creator(tenantCode)
                .deleted(false)
                .build();
        orderMapper.insert(entity);
        log.info("创建订单 tenant={} orderNo={} plan={} cycle={} amount={}",
                tenantCode, orderNo, plan.getPlanName(), cycle, amount);
        return new OrderCreatedVO(orderNo, tenantCode, plan.getId(), plan.getPlanName(),
                cycle, amount, 1, "待支付", now);
    }

    /**
     * 支付成功（确认收款 / 三方回调都走它）。
     *
     * <p>支付成功之后要干三件事，缺一件都会留下不一致：</p>
     * <ol>
     *   <li>通知 tenant-service 更新套餐与到期时间；</li>
     *   <li>把套餐配额同步成租户的限流配额（tenant_quota）；</li>
     *   <li>写消息中心通知（"套餐已生效"）——这里只记录日志，通知走既有的 notification。</li>
     * </ol>
     *
     * <p><b>支付方式也要在这里校验</b>：平台在「平台设置 → 支付配置」里关掉支付宝 / 微信之后，
     * 收银台会灰掉对应按钮，但**灰按钮不算数**——带 `payMethod=1` 直接打接口一样能过。
     * 所以这一层必须再看一次渠道开关，并且把真实支付方式落进订单
     * （原来这里硬编码写 3，导致收款流水里所有订单都显示"对公转账"）。</p>
     */
    @Transactional
    public OrderPaidVO payOrder(String tenantCode, String orderNo, long planId, int cycle,
                                Integer payMethod, String payNo, String operatorName) {
        Plan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new BizException(40001, "套餐不存在");
        }
        // 支付方式：1-支付宝、2-微信、3-对公转账；不传按对公转账处理（平台线下确认收款的场景）
        int method = payMethod == null ? PAY_METHOD_CORP : payMethod;
        if (method != PAY_METHOD_ALIPAY && method != PAY_METHOD_WECHAT && method != PAY_METHOD_CORP) {
            throw new BizException(40001, "支付方式不合法：" + method);
        }
        if (!settingsService.channelEnabled(method)) {
            throw new BizException(40302, method == PAY_METHOD_ALIPAY
                    ? "平台已关闭支付宝收款，请改用微信支付或对公转账"
                    : "平台已关闭微信收款，请改用支付宝或对公转账");
        }
        // 金额以**订单**为准：订单查不到就说明这不是一次合法的支付
        BillingOrder order = orderMapper.selectOne(Wrappers.<BillingOrder>lambdaQuery()
                .eq(BillingOrder::getTenantCode, tenantCode)
                .eq(BillingOrder::getOrderNo, orderNo)
                .eq(BillingOrder::getDeleted, false));
        if (order == null) {
            throw new BizException(40401, "订单不存在：" + orderNo);
        }
        if (order.getStatus() != null && order.getStatus() == 2) {
            throw new BizException(40001, "该订单已支付，请勿重复支付");
        }
        BigDecimal amount = order.getAmount() == null
                ? (order.getCycle() != null && order.getCycle() == CYCLE_YEARLY
                    ? plan.getPriceYearly() : plan.getPriceMonthly())
                : order.getAmount();
        int effectiveCycle = order.getCycle() == null ? cycle : order.getCycle();
        PlanQuota quota = quotaOf(plan);
        boolean notified = tenantPlanClient.applyPlan(tenantCode, plan.getId(), plan.getPlanName(),
                effectiveCycle, amount, quota.qpsLimit(), quota.dailyRequestLimit(),
                quota.dailySessionLimit(), quota.aiDailyLimit());
        order.setStatus(2);
        order.setPayMethod(method);
        order.setPayTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        order.setEditor("platform");
        orderMapper.updateById(order);
        // 收款流水：订单是"要收多少钱"的意向，这里才真的收到了钱——一笔订单一条流水
        // （重复支付在上面已被拦掉；三方回调重推则由 biz_no 唯一索引兜底）
        paymentRecordService.recordOrder(tenantCode, order.getId(), orderNo, method, amount,
                payNo, operatorName);
        return new OrderPaidVO(orderNo, 2, "已支付", LocalDateTime.now(), plan.getId(),
                plan.getPlanName(), amount, notified);
    }

    /**
     * 开通免费版试用：企业审核通过后由 tenant-service 调用。
     *
     * <p>"免费版"是套餐表里的一档真实套餐（价格 0、配额很小），不是代码里的特例——
     * 这样对比表、升级页、账单、限流全都同一套逻辑。试用期 1 个月，
     * 到期后要么升级，要么按免费版继续（配额用完会被网关限流）。</p>
     */
    @Transactional
    public FreeTrialVO startFreeTrial(String tenantCode) {
        return startFreeTrial(tenantCode, null);
    }

    /**
     * 开通免费版试用。
     *
     * @param trialDays 试用天数（可空）：来自平台「注册与审核」的策略值；
     *                  有值时 tenant 侧按"今天 + N 天"设到期时间，为空则按周期（一个月）
     */
    public FreeTrialVO startFreeTrial(String tenantCode, Integer trialDays) {
        Plan free = planMapper.selectOne(Wrappers.<Plan>lambdaQuery()
                .eq(Plan::getPlanCode, "FREE")
                .eq(Plan::getDeleted, false)
                .eq(Plan::getIsEnabled, true));
        if (free == null) {
            throw new BizException(50002, "免费版套餐不存在：先执行 bash scripts/migrate-billing-db.sh");
        }
        PlanQuota quota = quotaOf(free);
        boolean notified = tenantPlanClient.applyPlan(tenantCode, free.getId(), free.getPlanName(),
                CYCLE_MONTHLY, BigDecimal.ZERO, quota.qpsLimit(), quota.dailyRequestLimit(),
                quota.dailySessionLimit(), quota.aiDailyLimit(), trialDays);
        log.info("免费版试用已开通 tenant={} plan={} 会话配额={}",
                tenantCode, free.getPlanName(), free.getQuotaRobotSessions());
        return new FreeTrialVO(tenantCode, free.getId(), free.getPlanName(), true, trialDays,
                free.getQuotaRobotSessions(), notified);
    }

    /**
     * 套餐 → 网关配额。
     *
     * <p>套餐里的会话配额是**每月**的，而网关的日额度是按天算的，所以按"月 ÷ 30"折算
     * （至少给 10，避免小额套餐被折算成 0 直接不可用）。这样免费版"每月 500 次会话"
     * 落到网关就是"每天约 17 次"，用超了就是 429 + 提示升级。</p>
     */
    private static PlanQuota quotaOf(Plan plan) {
        int monthlySessions = plan.getQuotaRobotSessions() == null ? 0 : plan.getQuotaRobotSessions();
        int dailySessions = monthlySessions <= 0 ? 0 : Math.max(10, (int) Math.ceil(monthlySessions / 30.0));
        return new PlanQuota(
                qpsLimitOf(plan),
                monthlySessions <= 0 ? 0 : monthlySessions * 10,
                dailySessions,
                plan.getQuotaAiCalls() == null ? 0 : plan.getQuotaAiCalls());
    }

    /** 套餐对应的四类网关额度 */
    private record PlanQuota(int qpsLimit, int dailyRequestLimit, int dailySessionLimit, int aiDailyLimit) {
    }

    /** 订单列表：企业侧看自己的，平台侧可跨租户 */
    /**
     * 订单列表（企业侧：自己的；平台侧：可跨租户）。
     *
     * <p>{@code cycleKey} 按**支付时间**筛账期、{@code status} 筛状态
     * （平台页的「收款流水」就是 {@code status=2} 的已支付订单）——
     * 这两个参数让"真实收的钱"可以直接按账期对出来。</p>
     */
    public BillingOrderPageVO orders(String tenantCode, String status, String planId, String payCycle,
                                     String payMethod, String cycleKey, String payFrom, String payTo,
                                     String keyword, int page, int pageSize) {
        String scoped = Texts.blankToNull(tenantCode);
        Integer statusCode = intOrNull(status);
        Long plan = longOrNull(planId);
        Integer cycle = intOrNull(payCycle);
        Integer method = intOrNull(payMethod);
        String cycleFilter = Texts.blankToNull(cycleKey);
        String from = Texts.blankToNull(payFrom);
        String to = Texts.blankToNull(payTo);
        String kw = Texts.blankToNull(keyword);

        // 列表 / 计数 / 金额合计用同一套过滤条件（XML 里的 <sql id="orderFilters">），
        // 保证"页面上看到的行"和"合计金额"永远是一回事。
        long total = billingMapper.countOrders(scoped, statusCode, plan, cycle, method,
                cycleFilter, from, to, kw);
        BigDecimal amount = billingMapper.sumOrders(scoped, statusCode, plan, cycle, method,
                cycleFilter, from, to, kw);
        List<BillingOrder> list = billingMapper.selectOrders(scoped, statusCode, plan, cycle, method,
                cycleFilter, from, to, kw, pageSize, (long) (page - 1) * pageSize);

        return new BillingOrderPageVO(total, amount == null ? BigDecimal.ZERO : amount,
                page, pageSize, list);
    }

        private static Integer intOrNull(String value) {
        String text = Texts.blankToNull(value);
        return text == null ? null : Integer.valueOf(text);
    }

    private static Long longOrNull(String value) {
        String text = Texts.blankToNull(value);
        return text == null ? null : Long.valueOf(text);
    }

    /** 套餐 → 网关 QPS：按会话配额档位给（标准 20 / 专业 50 / 企业 200） */
    private static int qpsLimitOf(Plan plan) {
        int quota = plan.getQuotaRobotSessions() == null ? 0 : plan.getQuotaRobotSessions();
        if (quota >= 200000) {
            return 200;
        }
        if (quota >= 30000) {
            return 50;
        }
        return 20;
    }

    // ------------------------------------------------------------------ 账单

    /**
     * 生成/重算账期账单（幂等）。
     *
     * <p>金额 = 套餐费（按周期折算到月）+ 超量费（会话数超出套餐部分，每 1000 次 × 单价）。</p>
     */
    @Transactional
    public BillingStatement generateStatement(LoginUser operator, String tenantCode,
                                              String cycleKey, Long planId) {
        YearMonth cycle = parseCycle(cycleKey);
        // 不传 planId 时按租户**当前套餐**算钱（权威值在 tenant_db），
        // 否则平台页不填套餐就会生成一张 0 元账单
        PlanInfo planInfo = tenantPlanClient.planOf(tenantCode);
        Long effectivePlanId = planId == null ? planInfo.planId() : planId;
        Plan plan = effectivePlanId == null ? null : planMapper.selectById(effectivePlanId);
        if (plan == null) {
            throw new BizException(40001, "租户还没有开通套餐，无法生成账单（请先在「版本与升级」购买套餐）");
        }
        UsageSnapshotVO snapshot = usageMeterService.snapshot(tenantCode, cycle);
        long sessions = snapshot.sessions() == null ? 0L : snapshot.sessions();
        // 预付费周期内不再收套餐费：年付/月付订单已经把这段周期的套餐费付掉了，
        // 账期账单只结「后付费」的那部分（超量费）。否则年付客户会被按月重复收一遍钱。
        boolean prepaid = planInfo.expireTime() != null
                && !planInfo.expireTime().toLocalDate().isBefore(cycle.atEndOfMonth());
        BigDecimal planAmount = prepaid ? BigDecimal.ZERO : plan.getPriceMonthly();
        BigDecimal overageAmount = overage(plan, sessions);
        BigDecimal total = planAmount.add(overageAmount).setScale(2, RoundingMode.HALF_UP);
        String remark = prepaid
                ? "套餐费已预付（有效期至 " + planInfo.expireTime().toLocalDate() + "），本期只结超量费"
                : null;

        BillingStatement existing = statementMapper.selectOne(Wrappers.<BillingStatement>lambdaQuery()
                .eq(BillingStatement::getTenantCode, tenantCode)
                .eq(BillingStatement::getCycleKey, cycle.toString())
                .eq(BillingStatement::getDeleted, false));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            BillingStatement statement = BillingStatement.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenantCode)
                    .statementNo(nextStatementNo(cycle))
                    .cycleKey(cycle.toString())
                    .planId(plan == null ? null : plan.getId())
                    .planName(plan == null ? null : plan.getPlanName())
                    .planAmount(planAmount)
                    .overageAmount(overageAmount)
                    .totalAmount(total)
                    .status(STATEMENT_PENDING)
                    .dueDate(cycle.atEndOfMonth().plusDays(DUE_DAYS))
                    .usageSnapshot(toJson(snapshot))
                    .remark(remark)
                    .creator("system")
                    .createTime(now)
                    .updateTime(now)
                    .deleted(false)
                    .build();
            statementMapper.insert(statement);
            log.info("生成账单 tenant={} cycle={} 套餐费={}（预付={}）超量费={} 合计={}",
                    tenantCode, cycle, planAmount, prepaid, overageAmount, total);
            auditLogClient.record(operator, AUDIT_MODULE, "生成账单", "账单", statement.getStatementNo(), true,
                    "租户 " + tenantCode + " 账期 " + cycle + "：套餐费 " + planAmount
                            + " + 超量费 " + overageAmount + " = " + total);
            return statement;
        }
        // 已结算的账单不再重算（钱已经收了，不能因为再点一次生成就变数）
        if (existing.getStatus() != null && existing.getStatus() == STATEMENT_SETTLED) {
            throw new BizException(40001, "该账期已结算，不能重算（如需调整请先作废）");
        }
        existing.setPlanId(plan.getId());
        existing.setPlanName(plan.getPlanName());
        existing.setPlanAmount(planAmount);
        existing.setOverageAmount(overageAmount);
        existing.setTotalAmount(total);
        existing.setUsageSnapshot(toJson(snapshot));
        existing.setRemark(remark);
        existing.setUpdateTime(now);
        statementMapper.updateById(existing);
        log.info("重算账单 tenant={} cycle={} 合计={}", tenantCode, cycle, total);
        auditLogClient.record(operator, AUDIT_MODULE, "重算账单", "账单", existing.getStatementNo(), true,
                "租户 " + tenantCode + " 账期 " + cycle + " 重算为 " + total);
        return existing;
    }

    /** 结算（收钱）/ 作废 */
    @Transactional
    public BillingStatement settle(LoginUser operator, String tenantCode, String statementNo, boolean settle) {
        return settle(operator, tenantCode, statementNo, settle, null, null);
    }

    /**
     * 结算 / 作废账单。
     *
     * @param payMethodCode 收款方式码（1-支付宝、2-微信、3-对公）：结算时写进收款流水，不传按对公
     * @param payNo         三方流水号（对公转账一般没有，由平台生成收款单号）
     */
    public BillingStatement settle(LoginUser operator, String tenantCode, String statementNo,
                                   boolean settle, Integer payMethodCode, String payNo) {
        BillingStatement statement = statementMapper.selectOne(Wrappers.<BillingStatement>lambdaQuery()
                .eq(BillingStatement::getTenantCode, tenantCode)
                .eq(BillingStatement::getStatementNo, statementNo)
                .eq(BillingStatement::getDeleted, false));
        if (statement == null) {
            throw new BizException(40401, "账单不存在：" + statementNo);
        }
        if (settle && statement.getStatus() != null && statement.getStatus() == STATEMENT_SETTLED) {
            throw new BizException(40001, "该账单已结算");
        }
        statement.setStatus(settle ? STATEMENT_SETTLED : 4);
        statement.setSettleTime(settle ? LocalDateTime.now() : null);
        statement.setUpdateTime(LocalDateTime.now());
        statementMapper.updateById(statement);
        // 后付费收款：结算=真收到钱，写一条流水（作废不写）。账期账单没有订单，
        // 所以 biz_type=2、order_id 留空——这正是增量脚本放开 order_id 的原因
        if (settle) {
            int settleMethod = payMethodCode == null ? PaymentRecordService.METHOD_CORP : payMethodCode;
            paymentRecordService.recordStatement(tenantCode, statementNo, settleMethod,
                    statement.getTotalAmount(), payNo, operator == null ? null : operator.name());
        }
        // 结算/作废都动钱：审计里把金额一起写清楚
        auditLogClient.record(operator, AUDIT_MODULE, settle ? "结算账单" : "作废账单",
                "账单", statementNo, true,
                "租户 " + tenantCode + " 账单应付 " + statement.getTotalAmount()
                        + (settle ? "，已确认收款" : "，已作废（可重新生成）"));
        return statement;
    }

    public BillingStatementPageVO statements(String tenantCode, String cycleKey, Integer status,
                                             int page, int pageSize) {
        long total = billingMapper.countStatements(tenantCode, cycleKey, status);
        List<BillingStatement> list = billingMapper.selectStatements(tenantCode, cycleKey, status,
                pageSize, (long) (page - 1) * pageSize);
        return new BillingStatementPageVO(total, page, pageSize, list);
    }

    /** 平台概览：本月账单 / 已收 / 待收 / 逾期 + 套餐分布 */
    public BillingOverviewVO overview(String cycleKey) {
        String cycle = cycleKey == null || cycleKey.isBlank()
                ? YearMonth.now().toString() : cycleKey;
        Map<String, Object> raw = billingMapper.selectStatementOverview(cycle);
        // 「真实收的钱」来自订单，不是账单：预付费（买套餐）走订单，
        // 账期账单只承载后付费的超量费。两个数分开摆，才不会出现"付过钱却显示已收 0"。
        Map<String, Object> paid = billingMapper.selectPaymentOverview(cycle);
        List<PlanDistributionVO> distribution = new ArrayList<>();
        for (Map<String, Object> row : billingMapper.selectPlanDistribution(cycle)) {
            distribution.add(new PlanDistributionVO(String.valueOf(row.get("name")),
                    UsageMeterService.longValue(row.get("cnt")),
                    UsageMeterService.decimalValue(row.get("amount"))));
        }
        return new BillingOverviewVO(cycle,
                UsageMeterService.longValue(raw == null ? null : raw.get("statements")),
                UsageMeterService.decimalValue(raw == null ? null : raw.get("total_amount")),
                UsageMeterService.decimalValue(raw == null ? null : raw.get("settled_amount")),
                UsageMeterService.decimalValue(raw == null ? null : raw.get("pending_amount")),
                UsageMeterService.longValue(raw == null ? null : raw.get("overdue_count")),
                UsageMeterService.longValue(paid == null ? null : paid.get("paid_orders")),
                UsageMeterService.decimalValue(paid == null ? null : paid.get("paid_amount")),
                distribution);
    }

    /** 超量费：会话数超出套餐部分，按"每 1000 次 × 单价"计（套餐单价为 0 表示不允许超量） */
    private static BigDecimal overage(Plan plan, long sessions) {
        if (plan == null || plan.getQuotaRobotSessions() == null || plan.getQuotaRobotSessions() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal price = plan.getOveragePricePerK();
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        long over = sessions - plan.getQuotaRobotSessions();
        if (over <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(Math.ceil(over / 1000.0))
                .multiply(price)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static YearMonth parseCycle(String cycleKey) {
        if (cycleKey == null || cycleKey.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(cycleKey);
        } catch (Exception e) {
            throw new BizException(40001, "账期格式应为 YYYY-MM");
        }
    }

    private String nextOrderNo() {
        return "SO" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%03d", (int) (System.nanoTime() % 1000));
    }

    private String nextStatementNo(YearMonth cycle) {
        return "ST" + cycle.toString().replace("-", "") + String.format("%05d", (int) (System.nanoTime() % 100000));
    }

    /**
     * 用量快照序列化。
     *
     * <p>快照是一个**扁平**的 Map（账期 + 5 个数字），手写 JSON 就够，不引 JSON 库：
     * billing-service 的依赖已经很干净，为 5 个字段拉一个 Jackson 不划算。</p>
     */
    static String toJson(UsageSnapshotVO snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new StringBuilder("{")
                .append("\"cycleKey\":\"").append(snapshot.cycleKey()).append("\",")
                .append("\"sessions\":").append(number(snapshot.sessions())).append(',')
                .append("\"messages\":").append(number(snapshot.messages())).append(',')
                .append("\"aiCalls\":").append(number(snapshot.aiCalls())).append(',')
                .append("\"seats\":").append(number(snapshot.seats())).append(',')
                .append("\"storageGb\":").append(number(snapshot.storageGb()))
                .append('}')
                .toString();
    }

    private static String number(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    /** 让编译期知道这个常量被用到了（作废状态） */
    public static int cancelledStatus() {
        return 4;
    }

    /** 结算期限（天）：平台侧页面提示"还有几天到期"用 */
    public static int dueDays() {
        return DUE_DAYS;
    }

    /** 统一错误码（避免各处硬编码） */
    public static int paramError() {
        return ResultCode.BAD_REQUEST.getCode();
    }

    /** 账期首日：企业侧"当前账期用量"用 */
    public static LocalDate cycleStart(YearMonth cycle) {
        return cycle.atDay(1);
    }
}
