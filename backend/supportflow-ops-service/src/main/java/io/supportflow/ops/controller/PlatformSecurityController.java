package io.supportflow.ops.controller;

import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.ops.dto.ApiKeyBody;
import io.supportflow.ops.dto.ApiKeyListVO;
import io.supportflow.ops.dto.ApiKeyStatusVO;
import io.supportflow.ops.dto.CreatedApiKeyVO;
import io.supportflow.ops.dto.ExportPageVO;
import io.supportflow.ops.dto.ExportRowVO;
import io.supportflow.ops.dto.PolicyBody;
import io.supportflow.ops.dto.RemarkBody;
import io.supportflow.ops.dto.SecurityPolicyVO;
import io.supportflow.ops.service.ExportApprovalService;
import io.supportflow.ops.service.SecurityPolicyService;
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
 * 平台「安全中心」：安全策略 + API 密钥 + 导出审批。
 *
 * <p>安全事件（越权 / 限流 / RLS 拦截）的数据在 tenant_db，由 tenant-service 出接口，
 * 页面把两边合并展示。</p>
 */
@RestController
@RequestMapping("/api/ops/platform/security")
public class PlatformSecurityController {

    private final SecurityPolicyService policyService;
    private final ExportApprovalService exportService;

    public PlatformSecurityController(SecurityPolicyService policyService,
                                      ExportApprovalService exportService) {
        this.policyService = policyService;
        this.exportService = exportService;
    }

    // ------------------------------------------------------------------ 安全策略

    /** 读安全策略（没配过返回一套默认值，不写库）。 */
    @GetMapping("/policy")
    public ApiResponse<SecurityPolicyVO> policy() {
        requirePlatform();
        return ApiResponse.ok(policyService.policy());
    }

    /** 保存安全策略（upsert + 审计）。 */
    @PutMapping("/policy")
    public ApiResponse<SecurityPolicyVO> savePolicy(
            @RequestBody PolicyBody body
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(policyService.savePolicy(operator, body));
    }

    // ------------------------------------------------------------------ API 密钥

    /** API 密钥列表 + 概览（可按租户筛）。 */
    @GetMapping("/api-keys")
    public ApiResponse<ApiKeyListVO> apiKeys(
            @RequestParam(required = false) String tenantCode
    ) {
        requirePlatform();
        return ApiResponse.ok(policyService.apiKeys(tenantCode));
    }

    /** 生成密钥：明文只在这次响应里返回一次 */
    @PostMapping("/api-keys")
    public ApiResponse<CreatedApiKeyVO> createApiKey(
            @RequestBody ApiKeyBody body
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(policyService.createApiKey(operator, body));
    }

    /** 启用 / 停用 API 密钥。 */
    @PostMapping("/api-keys/{id}/status")
    public ApiResponse<ApiKeyStatusVO> updateApiKeyStatus(
            @PathVariable long id,
            @RequestParam int status
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(policyService.updateApiKeyStatus(operator, id, status));
    }

    // ------------------------------------------------------------------ 导出审批

    /** 导出申请单分页 + 概览（平台审批台）。 */
    @GetMapping("/exports")
    public ApiResponse<ExportPageVO> exports(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String tenantCode,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize
    ) {
        requirePlatform();
        return ApiResponse.ok(exportService.exports(status, tenantCode, page, pageSize));
    }

    /** 通过导出申请（会通知申请企业，一次审批只能导一次）。 */
    @PostMapping("/exports/{id}/approve")
    public ApiResponse<ExportRowVO> approve(
            @PathVariable long id,
            @RequestBody(required = false) RemarkBody body
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(exportService.approve(operator, id, body == null ? null : body.remark()));
    }

    /** 驳回导出申请（写原因并通知申请企业）。 */
    @PostMapping("/exports/{id}/reject")
    public ApiResponse<ExportRowVO> reject(
            @PathVariable long id,
            @RequestBody(required = false) RemarkBody body
    ) {
        LoginUser operator = requirePlatform();
        return ApiResponse.ok(exportService.reject(operator, id, body == null ? null : body.remark()));
    }

    private LoginUser requirePlatform() {
        LoginUser user = LoginUserContext.require();
        if (user.userType() != 1) {
            throw new BizException(40302, "只有平台账号能用安全中心");
        }
        return user;
    }
}
