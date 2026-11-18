package io.supportflow.ops.controller;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.ops.dto.ApiKeyVerifyVO;
import io.supportflow.ops.dto.ConsolePolicyVO;
import io.supportflow.ops.dto.ConsumeBody;
import io.supportflow.ops.dto.ConsumeByIdBody;
import io.supportflow.ops.dto.ExportDecisionVO;
import io.supportflow.ops.dto.ExportPendingVO;
import io.supportflow.ops.dto.ExportRequestBody;
import io.supportflow.ops.dto.ExportRequestVO;
import io.supportflow.ops.dto.LoginPolicyVO;
import io.supportflow.ops.dto.RegisterConfigVO;
import io.supportflow.ops.dto.SecurityPolicyVO;
import io.supportflow.ops.service.ExportApprovalService;
import io.supportflow.ops.service.PlatformSettingsService;
import io.supportflow.ops.service.SecurityPolicyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 安全中心对内的接口（不带平台账号鉴权，只给本机服务调用）。

 * <p>三条链路：</p>
 * <ul>
 *   <li>{@code /api-keys/verify}：网关校验 {@code X-Api-Key}，把密钥换成租户编码；</li>
 *   <li>{@code /login-policy}：user-service 登录链路取"失败锁定阈值"与"IP 白名单"；</li>
 *   <li>{@code /exports/consume} 与 {@code /exports/request}：customer-service 导出前校验、
 *       以及企业侧提交导出申请。</li>
 * </ul>
 *
 * <p>这些都是"执行点"，所以接口只做参数校验、不做角色判断——调用方是内部服务，
 * 外部请求根本到不了这里（网关只转发 /api/**，这些路径没有对外路由）。</p>
 */
@RestController
@RequestMapping("/api/ops/internal/security")
public class InternalSecurityController {

    private final SecurityPolicyService policyService;
    private final ExportApprovalService exportService;
    /** 注册审核策略（平台设置里那五个开关）：注册链路照它执行 */
    private final PlatformSettingsService settingsService;

    public InternalSecurityController(SecurityPolicyService policyService,
                                      ExportApprovalService exportService,
                                      PlatformSettingsService settingsService) {
        this.policyService = policyService;
        this.exportService = exportService;
        this.settingsService = settingsService;
    }

    /** 注册审核策略（user-service 注册链路调用：开不开注册、要不要审核、试用多少天、白名单） */
    @GetMapping("/registration-policy")
    public ApiResponse<RegisterConfigVO> registrationPolicy() {
        return ApiResponse.ok(settingsService.registerConfig());
    }

    /** 密钥校验：命中返回租户编码 */
    @GetMapping("/api-keys/verify")
    public ApiResponse<ApiKeyVerifyVO> verifyApiKey(@RequestParam(required = false) String apiKey) {
        String tenant = policyService.verifyApiKey(apiKey);
        return ApiResponse.ok(new ApiKeyVerifyVO(tenant != null, tenant));
    }

    /**
     * 管理端显示策略（只读）：只回"水印开关"与"空闲登出时长"两个字段。
     *
     * <p>为什么不复用上面的登录策略接口：那是登录链路的内部接口，字段（失败阈值/IP 白名单）
     * 属于安全敏感信息，不该给前端读；企业侧只需要知道"要不要显示水印、多久自动登出"。</p>
     */
    @GetMapping("/console-policy")
    public ApiResponse<ConsolePolicyVO> consolePolicy() {
        SecurityPolicyVO policy = policyService.policy();
        return ApiResponse.ok(new ConsolePolicyVO(policy.watermarkEnabled(),
                policy.sessionTimeoutMinutes()));
    }

    /** 登录策略（失败锁定阈值 + IP 白名单） */
    @GetMapping("/login-policy")
    public ApiResponse<LoginPolicyVO> loginPolicy() {
        return ApiResponse.ok(policyService.loginPolicy());
    }

    /**
     * 本租户可用的导出申请单（企业点消息里的下载链接时用）。
     *
     * <p>只回申请单号与申请时的筛选范围——范围由服务端提供，前端不传，
     * 「审批的是 A 条件、导出的是 B 条件」这种事从入口就不成立。</p>
     */
    @GetMapping("/exports/pending")
    public ApiResponse<ExportPendingVO> pending(@RequestParam(required = false) String tenantCode,
                                                    @RequestParam(defaultValue = "1") int exportType) {
        return ApiResponse.ok(exportService.pending(tenantCode, exportType));
    }

    /** 按申请单号精确消费（下载前回写实际导出行数；一次审批只对应一次导出） */
    @PostMapping("/exports/consume-by-id")
    public ApiResponse<ExportDecisionVO> consumeById(@RequestBody ConsumeByIdBody body) {
        if (body == null || body.tenantCode() == null) {
            return ApiResponse.ok(new ExportDecisionVO(false, "缺少租户", false, null));
        }
        return ApiResponse.ok(exportService.consumeById(body.tenantCode(), body.exportId(), body.rows()));
    }

    /** 导出前校验 + 留痕（customer-service 调用） */
    @PostMapping("/exports/consume")
    public ApiResponse<ExportDecisionVO> consume(@RequestBody ConsumeBody body) {
        if (body == null || body.tenantCode() == null) {
            return ApiResponse.ok(new ExportDecisionVO(false, "缺少租户", false, null));
        }
        return ApiResponse.ok(exportService.consume(body.tenantCode(), body.exportType(), body.rows()));
    }

    /** 企业侧提交导出申请（customer-service 调用） */
    @PostMapping("/exports/request")
    public ApiResponse<ExportRequestVO> request(@RequestBody ExportRequestBody body) {
        if (body == null || body.tenantCode() == null) {
            return ApiResponse.ok(new ExportRequestVO("", 0, false));
        }
        return ApiResponse.ok(exportService.request(body.tenantCode(), body.exportType(),
                body.scopeJson(), body.requesterId(), body.requesterName()));
    }
}
