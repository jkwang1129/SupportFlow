package io.supportflow.ops.controller;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.ops.dto.CostGuardVO;
import io.supportflow.ops.service.ObservabilityService;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/** Internal endpoint for the legacy customer-service AI cost guard. */
@RestController
@RequestMapping("/api/ops/internal/observability")
public class InternalObservabilityController {

    private static final Pattern TENANT_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,15}");
    private final ObservabilityService service;

    public InternalObservabilityController(ObservabilityService service) {
        this.service = service;
    }

    @GetMapping("/cost-guard")
    public ResponseEntity<ApiResponse<CostGuardVO>> costGuard(@RequestParam(required = false) String tenantCode) {
        String tenant = tenantCode == null ? "" : tenantCode.trim();
        if (!TENANT_CODE.matcher(tenant).matches() || "PLATFORM".equalsIgnoreCase(tenant)) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                    .body(ApiResponse.fail(40000, "无效的业务租户"));
        }
        try {
            return service.costGuard(tenant)
                    .map(guard -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(guard)))
                    .orElseGet(this::unavailable);
        } catch (DataAccessException | PersistenceException e) {
            return unavailable();
        }
    }

    private ResponseEntity<ApiResponse<CostGuardVO>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(ApiResponse.fail(50300, "暂时无法核实 AI 预算"));
    }
}
