package io.supportflow.customer.controller.quality;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.customer.dto.quality.AiBatchBody;
import io.supportflow.customer.dto.quality.AlertQuery;
import io.supportflow.customer.dto.quality.AlertVO;
import io.supportflow.customer.dto.quality.BatchAiResult;
import io.supportflow.customer.dto.quality.BatchReviewBody;
import io.supportflow.customer.dto.quality.BatchReviewResult;
import io.supportflow.customer.dto.quality.HandleAlertBody;
import io.supportflow.customer.dto.quality.ManualTaskBody;
import io.supportflow.customer.dto.quality.OverviewVO;
import io.supportflow.customer.dto.quality.ReviewBody;
import io.supportflow.customer.dto.quality.RuleBody;
import io.supportflow.customer.dto.quality.RuleVO;
import io.supportflow.customer.dto.quality.ScanResult;
import io.supportflow.customer.dto.quality.TaskDetailVO;
import io.supportflow.customer.dto.quality.TaskVO;
import io.supportflow.customer.service.quality.QaService;
import io.supportflow.customer.service.quality.RealtimeQaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 质检中心：看板、任务、扫描、复核与规则配置。
 */
@RestController
@RequestMapping("/api/customer/qa")
public class QaController {

    private final QaService qaService;
    private final RealtimeQaService realtimeQaService;

    public QaController(QaService qaService,
                        RealtimeQaService realtimeQaService) {
        this.qaService = qaService;
        this.realtimeQaService = realtimeQaService;
    }

    /**
     * 实时质检告警列表：质检中心「实时预警」用。
     */
    @GetMapping("/alerts")
    public ApiResponse<List<AlertVO>> alerts(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer severity,
            @RequestParam(required = false) Integer ruleType,
            @RequestParam(required = false) String sessionNo,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(realtimeQaService.alerts(user, new AlertQuery(
                status, severity, ruleType, sessionNo, keyword, startTime, endTime)));
    }

    /**
     * 某个会话的告警：坐席工作台切到这条会话时拉一次，之前错过的预警也不会漏。
     */
    @GetMapping("/alerts/session/{sessionNo}")
    public ApiResponse<List<AlertVO>> sessionAlerts(
            @PathVariable String sessionNo
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(realtimeQaService.alertsOfSession(user, sessionNo));
    }

    /**
     * 标记告警已处理（坐席当场处置完点一下）。
     */
    @PostMapping("/alerts/{id}/handle")
    public ApiResponse<AlertVO> handleAlert(
            @PathVariable String id,
            @RequestBody(required = false) HandleAlertBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(realtimeQaService.handle(user, id, body == null ? null : body.remark()));
    }

    /** 质检看板概览：任务量 / 风险分布 / 平均分（顶部卡片用）。 */
    @GetMapping("/overview")
    public ApiResponse<OverviewVO> overview() {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(qaService.overview(user));
    }

    /** 质检任务列表：可按状态与关键字筛。 */
    @GetMapping("/tasks")
    public ApiResponse<List<TaskVO>> tasks(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(qaService.tasks(user, status, keyword));
    }

    /** 任务详情（含规则逐条命中明细与会话原文）。 */
    @GetMapping("/tasks/{taskNo}")
    public ApiResponse<TaskDetailVO> detail(
            @PathVariable String taskNo
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(qaService.detail(user, taskNo));
    }

    /**
     * 发起全量质检：扫描"已结束、还没质检过"的真实会话，逐条建任务并跑 AI 初检。
     *
     * <p>会话一结束系统也会自动建任务，这里是把历史漏掉的补上；重复点不会重复建。</p>
     */
    @PostMapping("/scan")
    public ApiResponse<ScanResult> scan() {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(qaService.scan(user));
    }

    /** 针对某个会话手动建一条待复核任务（抽检用）。 */
    @PostMapping("/tasks/manual")
    public ApiResponse<TaskVO> createManual(
            @Valid @RequestBody ManualTaskBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(qaService.createManual(
                user,
                body.sessionName(),
                body.agentName(),
                body.aiScore(),
                body.riskLevel(),
                body.comment(),
                body.ruleNames()
        ));
    }

    /** 人工复核：1-通过、2-驳回、3-重检。 */
    @PostMapping("/tasks/{taskNo}/review")
    public ApiResponse<TaskVO> review(
            @PathVariable String taskNo,
            @Valid @RequestBody ReviewBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(qaService.review(user, taskNo, body.action(), body.score(), body.comment()));
    }

    /** 批量复核：多条任务一次性通过 / 驳回 / 重检。 */
    @PostMapping("/tasks/batch-review")
    public ApiResponse<BatchReviewResult> batchReview(
            @Valid @RequestBody BatchReviewBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(qaService.batchReview(
                user,
                body.taskNos(),
                body.action(),
                body.score(),
                body.comment()
        ));
    }

    /** 批量 AI 质检：对选中的任务重新跑规则与 AI 初检，并回到待复核队列。 */
    @PostMapping("/tasks/batch-ai")
    public ApiResponse<BatchAiResult> batchAi(
            @Valid @RequestBody AiBatchBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(qaService.batchAiCheck(user, body.taskNos()));
    }

    /** 质检规则列表。 */
    @GetMapping("/rules")
    public ApiResponse<List<RuleVO>> rules() {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(qaService.rules(user));
    }

    /** 新建 / 编辑质检规则。 */
    @PostMapping("/rules")
    public ApiResponse<RuleVO> saveRule(
            @Valid @RequestBody RuleBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(qaService.saveRule(
                user,
                body.id(),
                body.ruleName(),
                body.ruleType(),
                body.ruleContent(),
                body.weight(),
                body.enabled(),
                body.realtime() == null || body.realtime(),
                body.hitKeywords(),
                body.severity(),
                body.timeoutSeconds()
        ));
    }

    /** 删除质检规则。 */
    @DeleteMapping("/rules/{id}")
    public ApiResponse<Void> deleteRule(
            @PathVariable String id
    ) {
        LoginUser user = LoginUserContext.require();
        qaService.deleteRule(user, id);
        return ApiResponse.ok();
    }
}
