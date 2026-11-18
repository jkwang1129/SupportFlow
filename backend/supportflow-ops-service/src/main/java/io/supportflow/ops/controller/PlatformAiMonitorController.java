package io.supportflow.ops.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.ops.dto.AiMonitorVO;
import io.supportflow.ops.service.PlatformAiMonitorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * 平台「AI 监控」的用量与成本块：调用量 / token / 成本 / 耗时 / 成功率。
 *
 * <p>数据在 AI 服务的 ai_db（ai_usage_record），这里只做两件事：
 * ① 把"是不是平台账号"卡住（AI 服务本身不做鉴权，不能让它直接对公网开）；
 * ② 原样转出聚合结果，前端不直连 9100。判定与拼装都在
 * {@link PlatformAiMonitorService}，控制器只收参数、包响应。</p>
 */
@RestController
@RequestMapping("/api/ops/platform/ai-metrics")
public class PlatformAiMonitorController {

    private final PlatformAiMonitorService monitorService;

    public PlatformAiMonitorController(PlatformAiMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    /** AI 调用与成本监控（近 N 天调用量 / Token / 成本 / 按天分布 + 计量状态）。 */
    @GetMapping
    public ApiResponse<AiMonitorVO> metrics(
            @RequestParam(required = false) Integer days
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(monitorService.metrics(user, days));
    }
}
