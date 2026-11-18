package io.supportflow.tenant.admin.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.tenant.admin.dto.RejectBody;
import io.supportflow.tenant.admin.dto.ReviewPageVO;
import io.supportflow.tenant.admin.service.EnterpriseReviewAdminService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业审核台接口（仅平台管理员）。
 */
@RestController
@RequestMapping("/api/tenant/admin/reviews")
public class EnterpriseReviewAdminController {

    private final EnterpriseReviewAdminService service;

    public EnterpriseReviewAdminController(
            EnterpriseReviewAdminService service
    ) {
        this.service = service;
    }

    /** 企业审核台分页：可按状态 / 关键字筛。 */
    @GetMapping
    public ApiResponse<IPage<ReviewPageVO>> page(
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword
    ) {
        requirePlatform(LoginUserContext.require());
        return ApiResponse.ok(service.page(pageNum, pageSize, status, keyword));
    }

    /** 审核单详情（含提交的营业执照等材料）。 */
    @GetMapping("/{reviewId}")
    public ApiResponse<ReviewPageVO> detail(
            @PathVariable long reviewId
    ) {
        requirePlatform(LoginUserContext.require());
        return ApiResponse.ok(service.detail(reviewId));
    }

    /** 审核通过：企业转为正常，并按注册策略开通试用。 */
    @PostMapping("/{reviewId}/approve")
    public ApiResponse<ReviewPageVO> approve(
            @PathVariable long reviewId
    ) {
        LoginUser user = LoginUserContext.require();
        requirePlatform(user);
        return ApiResponse.ok(service.approve(user, reviewId));
    }

    /** 审核驳回：写原因，企业侧可修改后重新提交。 */
    @PostMapping("/{reviewId}/reject")
    public ApiResponse<ReviewPageVO> reject(
            @PathVariable long reviewId,
            @RequestBody RejectBody body
    ) {
        LoginUser user = LoginUserContext.require();
        requirePlatform(user);
        return ApiResponse.ok(service.reject(user, reviewId, body.reason()));
    }

    private void requirePlatform(LoginUser user) {
        if (user.userType() != 1) {
            throw new BizException(40301, "仅平台管理员可访问企业审核台");
        }
    }
}
