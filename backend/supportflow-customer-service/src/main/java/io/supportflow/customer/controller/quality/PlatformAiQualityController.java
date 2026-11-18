package io.supportflow.customer.controller.quality;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.dto.quality.AiQualityVO;
import io.supportflow.customer.service.quality.PlatformAiQualityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台「AI 监控」的质量与安全块：AI 质检结果 + 实时质检预警的跨租户聚合。
 *
 * <p>与 `/api/customer/qa/**` 分开挂：那边是租户内的质检业务（能看会话），
 * 这边是平台视角的聚合指标（只看"AI 判得准不准、风险拦没拦住"），鉴权口径不同。</p>
 */
@RestController
@RequestMapping("/api/customer/platform/ai-quality")
public class PlatformAiQualityController {

    private final PlatformAiQualityService qualityService;

    public PlatformAiQualityController(PlatformAiQualityService qualityService) {
        this.qualityService = qualityService;
    }

    /**
     * 平台 AI 质量与安全指标（近 N 天）：质检评分 + 实时预警。
     */
    @GetMapping
    public ApiResponse<AiQualityVO> quality(
            @RequestParam(required = false) Integer days
    ) {
        LoginUser user = LoginUserContext.require();
        if (user.userType() != 1) {
            throw new BizException(40302, "只有平台账号能看 AI 质量与安全指标");
        }
        return ApiResponse.ok(qualityService.quality(days == null ? 7 : days));
    }
}
