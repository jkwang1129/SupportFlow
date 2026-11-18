package io.supportflow.tenant.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.tenant.dto.PlatformAnalyticsVO;
import io.supportflow.tenant.service.PlatformAnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台数据（原型「平台数据」）：平台经营分析的租户侧聚合。
 *
 * <p>只给聚合视图：租户增长、套餐分布、用量 Top、租户列表（企业 + 套餐 + 状态 + 本月用量）。
 * "收了多少钱"在 billing-service 的平台分析接口里，页面把两边拼起来看。</p>
 */
@RestController
@RequestMapping("/api/tenant/admin/analytics")
public class PlatformAnalyticsController {

    private final PlatformAnalyticsService analyticsService;

    public PlatformAnalyticsController(PlatformAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /** 平台经营分析：一次把页面要的聚合全给了（省得前端串 5 个请求） */
    @GetMapping
    public ApiResponse<PlatformAnalyticsVO> analytics(
            @RequestParam(required = false) Integer months,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status
    ) {
        requirePlatform(LoginUserContext.require());
        return ApiResponse.ok(analyticsService.analytics(
                months == null ? 8 : months,
                page == null ? 1 : page,
                pageSize == null ? 10 : pageSize,
                keyword, status));
    }

    private void requirePlatform(LoginUser user) {
        if (user == null || user.userType() != 1) {
            throw new BizException(40302, "只有平台账号能看平台经营数据");
        }
    }
}
