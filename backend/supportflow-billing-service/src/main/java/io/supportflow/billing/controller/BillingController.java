package io.supportflow.billing.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.billing.dto.BatchGenerateVO;
import io.supportflow.billing.dto.BillingOrderPageVO;
import io.supportflow.billing.dto.BillingOverviewVO;
import io.supportflow.billing.dto.BillingStatementPageVO;
import io.supportflow.billing.dto.ConsolePaymentConfigVO;
import io.supportflow.billing.dto.FreeTrialVO;
import io.supportflow.billing.dto.OrderBody;
import io.supportflow.billing.dto.OrderCreatedVO;
import io.supportflow.billing.dto.OrderPaidVO;
import io.supportflow.billing.dto.PublicPlanVO;
import io.supportflow.billing.dto.UsageCurrentVO;
import io.supportflow.billing.dto.UsageReport;
import io.supportflow.billing.entity.BillingStatement;
import io.supportflow.billing.entity.Plan;
import io.supportflow.billing.service.BillingService;
import io.supportflow.billing.service.BillingStatementBatchService;
import io.supportflow.billing.service.PlatformBillingSettingsService;
import io.supportflow.billing.service.UsageMeterService;
import io.supportflow.common.api.AcceptedCountVO;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.api.ServicePing;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.context.TenantContext;
import io.supportflow.common.exception.BizException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 计费接口：企业侧（自己的套餐/用量/账单）与平台侧（跨租户账单与收入）共用一组路径，
 * 靠登录态里的 user_type 区分——平台账号（1）可以带 tenantCode 看任意租户。
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final BillingService billingService;
    private final UsageMeterService usageMeterService;
    /** 批量出账：一次给所有活跃租户生成某账期账单（月度任务与平台页的「生成全部」都走它） */
    private final BillingStatementBatchService statementBatchService;
    /** 支付配置：收银台要按渠道开关决定显示哪些支付方式（读接口走它） */
    private final PlatformBillingSettingsService settingsService;

    public BillingController(BillingService billingService,
                             UsageMeterService usageMeterService,
                             BillingStatementBatchService statementBatchService,
                             PlatformBillingSettingsService settingsService) {
        this.billingService = billingService;
        this.usageMeterService = usageMeterService;
        this.statementBatchService = statementBatchService;
        this.settingsService = settingsService;
    }

    /** 健康探针（与其它服务的 /ping 一致）：确认服务起来了、租户上下文能透传 */
    @GetMapping("/ping")
    public ApiResponse<ServicePing> ping() {
        return ApiResponse.ok(new ServicePing("billing-service",
                TenantContext.get() == null ? "" : TenantContext.get()));
    }

    /** 套餐列表（企业侧"版本与升级"与平台侧都用） */
    @GetMapping("/plans")
    public ApiResponse<List<Plan>> plans() {
        LoginUserContext.require();
        return ApiResponse.ok(billingService.plans());
    }

    /**
     * 收银台读的支付配置（**登录即可读**：企业账号要按它决定显示哪些支付方式）。
     *
     * <p>为什么不让企业去读平台那份配置接口：那份含未掩码的商户号与全部渠道开关，
     * 属于平台运营视角；收银台只需要"哪个方式能用 + 对公账户信息"。</p>
     */
    @GetMapping("/payment-config")
    public ApiResponse<ConsolePaymentConfigVO> consolePaymentConfig() {
        LoginUserContext.require();
        return ApiResponse.ok(settingsService.consolePaymentConfig());
    }

    /**
     * 官网定价用的**公开**套餐列表（不需要登录）。
     *
     * <p>官网是给还没注册的人看的，不能让它先登录。这里只回"上架且启用"的套餐，
     * 字段与内部接口一致——外部报价与后台开通用同一套数据，避免官网写死价格后对不上。</p>
     */
    @GetMapping("/public/plans")
    public ApiResponse<List<PublicPlanVO>> publicPlans() {
        List<PublicPlanVO> list = new ArrayList<>();
        for (Plan plan : billingService.plans()) {
            list.add(new PublicPlanVO(plan.getPlanCode(), plan.getPlanName(),
                    plan.getPriceYearly(), plan.getPriceMonthly(),
                    plan.getQuotaRobotSessions(), plan.getQuotaChannels(), plan.getQuotaSeats(),
                    plan.getQuotaStorageGb(), plan.getQuotaAiCalls(),
                    plan.getQuotaAiQa(), plan.getQuotaVision(),
                    plan.getOveragePricePerK()));
        }
        return ApiResponse.ok(list);
    }

    /** 当前账期用量 vs 套餐配额 */
    @GetMapping("/usage")
    public ApiResponse<UsageCurrentVO> usage(
            @RequestParam(required = false) String tenantCode,
            @RequestParam(required = false) Long planId
    ) {
        LoginUser user = LoginUserContext.require();
        String target = resolveTenant(user, tenantCode);
        return ApiResponse.ok(usageMeterService.currentUsage(target, planId));
    }

    /**
     * 订单列表（企业侧只看自己；平台侧可跨租户）。
     *
     * <p>平台页的「收款流水」就是 {@code status=2&cycleKey=2026-09}：
     * 按**支付时间**筛账期，看的是**真实收到的钱**。</p>
     */
    @GetMapping("/orders")
    public ApiResponse<BillingOrderPageVO> orders(
            @RequestParam(required = false) String tenantCode,
            @RequestParam(required = false) String cycleKey,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String planId,
            @RequestParam(required = false) String payCycle,
            @RequestParam(required = false) String payMethod,
            @RequestParam(required = false) String payFrom,
            @RequestParam(required = false) String payTo,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        LoginUser user = LoginUserContext.require();
        // 平台账号：不给 tenantCode 就是跨租户看全平台（收款流水页要的）；
        // 企业账号：只能看自己的，借参数看别人直接拒绝（与 /usage 一样，越权尝试要留痕）
        String target;
        if (user.userType() == 1) {
            target = tenantCode;
        } else {
            if (tenantCode != null && !tenantCode.isBlank() && !tenantCode.equals(user.tenantCode())) {
                throw new BizException(40303, "请求租户与登录身份不一致");
            }
            target = user.tenantCode();
        }
        return ApiResponse.ok(billingService.orders(target, status, planId, payCycle, payMethod,
                cycleKey, payFrom, payTo, keyword,
                Math.max(1, page), Math.min(Math.max(1, pageSize), 100)));
    }

    /** 账期账单列表（企业侧看自己，平台侧可跨租户）。 */
    @GetMapping("/statements")
    public ApiResponse<BillingStatementPageVO> statements(
            @RequestParam(required = false) String tenantCode,
            @RequestParam(required = false) String cycleKey,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        LoginUser user = LoginUserContext.require();
        String target = user.userType() == 1 ? (tenantCode == null ? "" : tenantCode) : user.tenantCode();
        return ApiResponse.ok(billingService.statements(target, cycleKey, status,
                Math.max(1, page), Math.min(Math.max(1, pageSize), 100)));
    }

    /** 平台概览：本月账单 / 已收 / 待收 / 逾期 + 套餐分布（仅平台账号） */
    @GetMapping("/overview")
    public ApiResponse<BillingOverviewVO> overview(
            @RequestParam(required = false) String cycleKey
    ) {
        requirePlatform(LoginUserContext.require());
        return ApiResponse.ok(billingService.overview(cycleKey));
    }

    /** 下单（企业侧选套餐 + 周期） */
    @PostMapping("/orders")
    public ApiResponse<OrderCreatedVO> createOrder(
            @RequestBody OrderBody body
    ) {
        LoginUser user = LoginUserContext.require();
        String target = resolveTenant(user, body.tenantCode());
        return ApiResponse.ok(billingService.createOrder(target, body.planId(), body.cycle()));
    }

    /**
     * 确认收款 / 三方支付回调：支付成功后套餐立即生效。
     *
     * <p>真实环境里这一步由支付网关回调触发（要验签），这里做成显式接口：
     * 企业侧"待付款"订单可由平台确认收款，也可以由前端在本地联调时直接调。</p>
     *
     * <p>`payMethod` 一定要传上来：一是要落进订单（不然收款流水里所有订单都显示同一个方式），
     * 二是**服务端要按渠道开关校验**——前端把支付宝按钮灰掉只是提示，这里拒绝才算真的关掉。</p>
     */
    @PostMapping("/orders/{orderNo}/pay")
    public ApiResponse<OrderPaidVO> payOrder(
            @PathVariable String orderNo,
            @RequestBody OrderBody body
    ) {
        LoginUser user = LoginUserContext.require();
        String target = resolveTenant(user, body.tenantCode());
        return ApiResponse.ok(billingService.payOrder(target, orderNo, body.planId(), body.cycle(),
                body.payMethod(), body.payNo(), user.name()));
    }

    /**
     * 账单结算 / 作废（平台侧收钱、作废错账）。
     *
     * <p>结算=真收到钱，会写一条收款流水（`payment_record`，biz_type=2）；
     * 作废只是撤销应付，不写流水。收款方式可选（不传按对公转账）。</p>
     */
    @PostMapping("/statements/{statementNo}/settle")
    public ApiResponse<BillingStatement> settle(
            @PathVariable String statementNo,
            @RequestParam(required = false) String tenantCode,
            @RequestParam(defaultValue = "true") boolean settle,
            @RequestParam(required = false) Integer payMethodCode,
            @RequestParam(required = false) String payNo
    ) {
        LoginUser user = LoginUserContext.require();
        requirePlatform(user);
        return ApiResponse.ok(billingService.settle(user, tenantCode, statementNo, settle,
                payMethodCode, payNo));
    }

    /** 生成 / 重算账期账单（平台侧按月跑，也可手动触发；幂等） */
    @PostMapping("/statements/generate")
    public ApiResponse<BillingStatement> generate(
            @RequestParam String tenantCode,
            @RequestParam(required = false) String cycleKey,
            @RequestParam(required = false) Long planId
    ) {
        LoginUser user = LoginUserContext.require();
        requirePlatform(user);
        return ApiResponse.ok(billingService.generateStatement(user, tenantCode, cycleKey, planId));
    }

    /**
     * 给**所有活跃租户**生成某账期的账单（平台侧手动补跑月度出账）。
     *
     * <p>与每月 1 号的自动任务走同一段逻辑：逐户独立事务，一家失败（例如该账期已结算）
     * 只记进结果里，不影响其它家。不传 {@code cycleKey} 默认本月。</p>
     */
    @PostMapping("/statements/generate-all")
    public ApiResponse<BatchGenerateVO> generateAll(
            @RequestParam(required = false) String cycleKey
    ) {
        LoginUser user = LoginUserContext.require();
        requirePlatform(user);
        String cycle = cycleKey == null || cycleKey.isBlank()
                ? YearMonth.now().toString() : cycleKey;
        return ApiResponse.ok(statementBatchService.generateForActiveTenants(user, cycle));
    }

    /**
     * 用量上报（内部）：由 customer-service（会话/消息/AI 调用）或网关侧计量调用。
     * 幂等累加，同一天重复上报只是加上去。
     */
    /** 开通免费版试用（内部）：企业审核通过后由 tenant-service 调用，让新租户能直接试用 */
    @PostMapping("/internal/tenants/{tenantCode}/trial")
    public ApiResponse<FreeTrialVO> startTrial(
            @PathVariable String tenantCode,
            @RequestParam(required = false) Integer days) {
        return ApiResponse.ok(billingService.startFreeTrial(tenantCode, days));
    }

    /**
     * 用量上报（内部）：由 customer-service（会话 / 消息 / AI 调用）或网关侧计量调用。
     * 幂等累加，同一天重复上报只是加上去。
     */
    @PostMapping("/internal/usage/report")
    public ApiResponse<AcceptedCountVO> reportUsage(@RequestBody UsageReport body) {
        usageMeterService.report(body.tenantCode(),
                body.statDate() == null ? LocalDate.now() : body.statDate(),
                body.sessions(), body.messages(), body.aiCalls(), body.seats(),
                body.storageGb() == null ? BigDecimal.ZERO : body.storageGb());
        return ApiResponse.ok(new AcceptedCountVO(1));
    }

    /** 企业侧只能看自己的；平台账号必须显式给 tenantCode */
    private static String resolveTenant(LoginUser user, String tenantCode) {
        if (user.userType() == 1) {
            if (tenantCode == null || tenantCode.isBlank()) {
                throw new BizException(40001, "平台账号查看租户数据时必须指定 tenantCode");
            }
            return tenantCode;
        }
        if (tenantCode != null && !tenantCode.isBlank() && !tenantCode.equals(user.tenantCode())) {
            // 企业账号想借参数看别人：直接拒绝（越权尝试留给网关与安全事件记录）
            throw new BizException(40303, "请求租户与登录身份不一致");
        }
        return user.tenantCode();
    }

    private static void requirePlatform(LoginUser user) {
        if (user == null || user.userType() != 1) {
            throw new BizException(40302, "只有平台管理员能看平台计费数据");
        }
    }
}
