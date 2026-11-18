package io.supportflow.tenant.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.tenant.dto.GuideState;
import io.supportflow.tenant.dto.ProfileBody;
import io.supportflow.tenant.dto.ProfileRequest;
import io.supportflow.tenant.service.EnterpriseOnboardingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业开通引导接口（企业用户自助：完善资料 / 提交审核 / 状态查询）。
 * 营业执照附件由 customer-service 文件接口统一负责上传与预览。
 */
@RestController
@RequestMapping("/api/tenant/enterprise/my")
public class EnterpriseOnboardingController {

    private final EnterpriseOnboardingService onboardingService;

    public EnterpriseOnboardingController(
            EnterpriseOnboardingService onboardingService
    ) {
        this.onboardingService = onboardingService;
    }

    /**
     * 查询当前企业开通引导状态。
     */
    @GetMapping
    public ApiResponse<GuideState> state() {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(onboardingService.getState(user));
    }

    /**
     * 暂存企业资料。
     */
    @PostMapping("/profile")
    public ApiResponse<GuideState> saveProfile(
            @Valid @RequestBody ProfileBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(onboardingService.saveProfile(user, toRequest(body)));
    }

    /**
     * 提交企业资料进入审核。
     */
    @PostMapping("/submit")
    public ApiResponse<GuideState> submit(
            @Valid @RequestBody ProfileBody body
    ) {
        LoginUser user = LoginUserContext.require();
        return ApiResponse.ok(onboardingService.submitReview(user, toRequest(body)));
    }

    private ProfileRequest toRequest(ProfileBody body) {
        return new ProfileRequest(
                body.companyName(),
                body.industry(),
                body.scale(),
                body.licenseNo(),
                body.registerAddress(),
                body.legalPerson(),
                body.licenseFileId(),
                body.contactName(),
                body.contactPhone(),
                body.contactEmail()
        );
    }
}
