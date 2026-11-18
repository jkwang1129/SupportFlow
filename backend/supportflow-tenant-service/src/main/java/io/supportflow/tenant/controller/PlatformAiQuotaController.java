package io.supportflow.tenant.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.tenant.dto.AiQuotaVO;
import io.supportflow.tenant.service.AiQuotaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台「AI 监控」的租户配额块：每个租户的 AI 日配额与今日用量、使用率与状态。
 *
 * <p>用量来自 AI 服务（真实调用计量），限额来自 tenant_db，两边在服务层拼好再返回，
 * 前端不用自己做 join。</p>
 */
@RestController
@RequestMapping("/api/tenant/admin/ai-quota")
public class PlatformAiQuotaController {

    private final AiQuotaService quotaService;

    public PlatformAiQuotaController(AiQuotaService quotaService) {
        this.quotaService = quotaService;
    }

    /** 各租户的 AI 配额用量大盘（今日调用 / Token / 成本 / 超限状态）。 */
    @GetMapping
    public ApiResponse<AiQuotaVO> quota() {
        LoginUser user = LoginUserContext.require();
        if (user.userType() != 1) {
            throw new BizException(40302, "只有平台账号能看租户配额");
        }
        return ApiResponse.ok(quotaService.quotaView());
    }
}
