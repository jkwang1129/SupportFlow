package io.supportflow.tenant.controller;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.tenant.dto.DraftBody;
import io.supportflow.tenant.dto.DraftCommand;
import io.supportflow.tenant.dto.DraftResult;
import io.supportflow.tenant.service.EnterpriseDraftService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部接口：仅供 user-service 注册流程调用（企业资料落库）。
 */
@RestController
@RequestMapping("/api/tenant/internal")
public class InternalEnterpriseController {

    private final EnterpriseDraftService draftService;

    public InternalEnterpriseController(EnterpriseDraftService draftService) {
        this.draftService = draftService;
    }

    /**
     * 创建企业草稿。
     */
    @PostMapping("/enterprises/draft")
    public ApiResponse<DraftResult> createDraft(@RequestBody DraftBody body) {
        DraftResult result = draftService.createDraft(
                new DraftCommand(
                        body.applicantId(),
                        body.companyName(),
                        body.industry(),
                        body.scale(),
                        body.contactName(),
                        body.contactPhone(),
                        body.contactEmail()
                )
        );
        return ApiResponse.ok(result);
    }
}
