package io.supportflow.billing.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.billing.dto.RevenueAnalyticsVO;
import io.supportflow.billing.service.PlatformRevenueService;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台数据页的"钱"这一半：平台收入。
 *
 * <p>租户规模、套餐分布、用量在 tenant-service（{@code /api/tenant/admin/analytics}），
 * 这里是计费库的收入口径。页面两边一起拉，按域分开、谁也不跨库读别人的表。</p>
 */
@RestController
@RequestMapping("/api/billing/platform/analytics")
public class PlatformRevenueController {

    private final PlatformRevenueService revenueService;

    public PlatformRevenueController(PlatformRevenueService revenueService) {
        this.revenueService = revenueService;
    }

    /**
     * 平台收入：累计实收、本月实收、按月趋势、按套餐、按支付方式、后付费账期
     */
    @GetMapping
    public ApiResponse<RevenueAnalyticsVO> analytics(
            @RequestParam(required = false) Integer months
    ) {
        requirePlatform(LoginUserContext.require());
        return ApiResponse.ok(revenueService.analytics(months == null ? 6 : months));
    }

    private void requirePlatform(LoginUser user) {
        if (user == null || user.userType() != 1) {
            throw new BizException(40302, "只有平台账号能看平台收入");
        }
    }
}
