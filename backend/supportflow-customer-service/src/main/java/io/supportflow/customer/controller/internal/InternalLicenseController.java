package io.supportflow.customer.controller.internal;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.CustomerInternalCredential.Caller;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.context.TenantContext;
import io.supportflow.customer.security.CustomerPrivilegeFilter;
import io.supportflow.customer.security.InternalCustomerEndpoint;
import io.supportflow.customer.service.license.EnterpriseLicenseFileService;
import org.springframework.web.bind.annotation.*;

/** Only the signed tenant service may validate a pre-onboarding file reference. */
@RestController
@RequestMapping("/api/customer/internal/license")
@InternalCustomerEndpoint(value = Caller.TENANT, tenantScoped = false)
public class InternalLicenseController {
    private final EnterpriseLicenseFileService service;
    public InternalLicenseController(EnterpriseLicenseFileService service) { this.service = service; }

    @GetMapping("/{fileId}/owner/{applicantId}")
    public ApiResponse<Boolean> owner(@PathVariable long fileId, @PathVariable long applicantId) {
        LoginUser applicant = new LoginUser(applicantId, "", "", 2, "");
        return TenantContext.bind(CustomerPrivilegeFilter.licenseScope(applicant),
                () -> ApiResponse.ok(service.ownedBy(applicant, fileId)));
    }
}
