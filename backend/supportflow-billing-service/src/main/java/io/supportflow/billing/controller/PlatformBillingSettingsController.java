package io.supportflow.billing.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.billing.dto.PaymentConfigBody;
import io.supportflow.billing.dto.PaymentConfigVO;
import io.supportflow.billing.dto.PlanAdminVO;
import io.supportflow.billing.dto.PlanPriceBody;
import io.supportflow.billing.service.PlatformBillingSettingsService;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.api.SavedCountVO;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台设置里的"钱"两块：套餐价格 + 支付配置。
 *
 * <p>页面把平台设置拼成一个页面，后端按域分开：注册策略与公告在 ops-service，
 * 这两块在 billing-service（钱的库在这边，写入口只有这一个）。</p>
 */
@RestController
@RequestMapping("/api/billing/platform/settings")
public class PlatformBillingSettingsController {

    private final PlatformBillingSettingsService settingsService;

    public PlatformBillingSettingsController(PlatformBillingSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * 套餐列表（含停用；平台设置页改价用）
     */
    @GetMapping("/plans")
    public ApiResponse<List<PlanAdminVO>> plans() {
        requirePlatform();
        return ApiResponse.ok(settingsService.plans());
    }

    /**
     * 保存套餐价格（保存后「版本与升级」页同步生效）
     */
    @PutMapping("/plans")
    public ApiResponse<SavedCountVO> savePlans(
            @RequestBody List<PlanPriceBody> body
    ) {
        LoginUser operator = requirePlatform();
        int saved = settingsService.savePlans(operator, body);
        return ApiResponse.ok(new SavedCountVO(saved));
    }

    /**
     * 读支付配置（三个渠道聚合成一张表单）
     */
    @GetMapping("/payment-config")
    public ApiResponse<PaymentConfigVO> paymentConfig() {
        requirePlatform();
        return ApiResponse.ok(settingsService.paymentConfig());
    }

    /**
     * 保存支付配置
     */
    @PutMapping("/payment-config")
    public ApiResponse<PaymentConfigVO> savePaymentConfig(
            @RequestBody PaymentConfigBody body
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(settingsService.savePaymentConfig(operator, body));
    }

    private LoginUser requirePlatform() {
        LoginUser user = LoginUserContext.require();
        if (user.userType() != 1) {
            throw new BizException(40302, "只有平台账号能改平台设置");
        }
        return user;
    }
}
