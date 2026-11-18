package io.supportflow.ops.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.api.CountVO;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.ops.dto.AiTraceVO;
import io.supportflow.ops.dto.AlertEventPageVO;
import io.supportflow.ops.dto.AlertHandleVO;
import io.supportflow.ops.dto.AlertRuleListVO;
import io.supportflow.ops.dto.BudgetBody;
import io.supportflow.ops.dto.BudgetListVO;
import io.supportflow.ops.dto.EvaluateResultVO;
import io.supportflow.ops.dto.HandleBody;
import io.supportflow.ops.dto.RuleBody;
import io.supportflow.ops.service.ObservabilityEvaluator;
import io.supportflow.ops.service.ObservabilityService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * 平台「可观测」：告警规则 / 告警事件 / 成本预算 / 调用链查询。
 *
 * <p>与「AI 监控」的分工：监控页回答"花了多少、效果如何"（只读大盘），
 * 这一页回答"什么算异常、超了怎么办、这个请求号到底发生了什么"——
 * 前者是看板，后者是**能动手的地方**（改规则、处置事件、配预算与超限动作）。</p>
 */
@RestController
@RequestMapping("/api/ops/platform/observability")
public class PlatformObservabilityController {

    private final ObservabilityService service;
    private final ObservabilityEvaluator evaluator;

    public PlatformObservabilityController(ObservabilityService service,
                                           ObservabilityEvaluator evaluator) {
        this.service = service;
        this.evaluator = evaluator;
    }

    // ------------------------------------------------------------------ 告警规则

    /** 告警规则列表 + 指标下拉选项（规则编辑器用）。 */
    @GetMapping("/rules")
    public ApiResponse<AlertRuleListVO> rules() {
        requirePlatform();
        return ApiResponse.ok(service.rules());
    }

    /** 新建告警规则。 */
    @PostMapping("/rules")
    public ApiResponse<AlertRuleListVO> createRule(
            @RequestBody RuleBody body
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(service.saveRule(operator, body, null));
    }

    /** 修改告警规则。 */
    @PutMapping("/rules/{id}")
    public ApiResponse<AlertRuleListVO> updateRule(
            @PathVariable long id,
            @RequestBody RuleBody body
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(service.saveRule(operator, body, id));
    }

    /** 删除告警规则（逻辑删除）。 */
    @DeleteMapping("/rules/{id}")
    public ApiResponse<AlertRuleListVO> deleteRule(
            @PathVariable long id
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(service.deleteRule(operator, id));
    }

    // ------------------------------------------------------------------ 告警事件

    /** 告警事件分页：可按处置状态 / 级别 / 租户筛，带概览与分类统计。 */
    @GetMapping("/events")
    public ApiResponse<AlertEventPageVO> events(
            @RequestParam(required = false) Integer handleStatus,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) String tenantCode,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize
    ) {
        requirePlatform();
        return ApiResponse.ok(service.events(handleStatus, level, tenantCode, page, pageSize));
    }

    /** 待处理条数：菜单角标（与事件页同一口径） */
    @GetMapping("/events/pending-count")
    public ApiResponse<CountVO> pendingCount() {
        requirePlatform();
        return ApiResponse.ok(new CountVO(service.pendingCount()));
    }

    /** 处置告警：2-已确认（真实异常）、3-已忽略（误报）。 */
    @PostMapping("/events/{id}/handle")
    public ApiResponse<AlertHandleVO> handle(
            @PathVariable long id,
            @RequestBody HandleBody body
    ) {
        LoginUser operator = requirePlatform();
        int status = body == null || body.status() == null ? 2 : body.status();
        return ApiResponse.ok(service.handle(operator, id, status, body == null ? null : body.remark()));
    }

    /** 手动跑一轮评估：刚改完规则不用等定时任务，也能立刻验证规则是否命中 */
    @PostMapping("/evaluate")
    public ApiResponse<EvaluateResultVO> evaluate() {
        requirePlatform();
        int[] result = evaluator.runOnce();
        return ApiResponse.ok(new EvaluateResultVO(result[0], result[1]));
    }

    // ------------------------------------------------------------------ 成本预算

    /** 成本预算列表 + 超限动作下拉选项。 */
    @GetMapping("/budgets")
    public ApiResponse<BudgetListVO> budgets() {
        requirePlatform();
        return ApiResponse.ok(service.budgets());
    }

    /** 保存某租户的成本预算（月度额度 / 预警比例 / 超限动作）。 */
    @PostMapping("/budgets")
    public ApiResponse<BudgetListVO> saveBudget(
            @RequestBody BudgetBody body
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(service.saveBudget(operator, body));
    }

    // ------------------------------------------------------------------ 调用链

    /** 按调用链号查这次请求在 AI 侧发生了什么（AI 服务出数，这里只做鉴权与转发）。 */
    @GetMapping("/trace")
    public ApiResponse<AiTraceVO> trace(
            @RequestParam(required = false) String requestId
    ) {
        requirePlatform();
        return ApiResponse.ok(service.trace(requestId));
    }

    private LoginUser requirePlatform() {
        LoginUser user = LoginUserContext.require();
        if (user.userType() != 1) {
            throw new BizException(40302, "只有平台账号能用可观测页");
        }
        return user;
    }
}
