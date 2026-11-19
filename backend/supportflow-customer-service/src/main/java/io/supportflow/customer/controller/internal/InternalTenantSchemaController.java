package io.supportflow.customer.controller.internal;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.CustomerInternalCredential.Caller;
import io.supportflow.customer.dto.tenant.SchemaCatalogVO;
import io.supportflow.customer.dto.tenant.Step;
import io.supportflow.customer.dto.tenant.TenantSchemaStatusVO;
import io.supportflow.customer.dto.tenant.UpgradeResult;
import io.supportflow.customer.security.CustomerInternalCall;
import io.supportflow.customer.security.InternalCustomerEndpoint;
import io.supportflow.customer.service.tenant.TenantSchemaCatalog;
import io.supportflow.customer.service.tenant.TenantSchemaUpgradeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内部接口：供 tenant-service（平台控制面）调用，对某个租户的库做升级。
 *
 * <p>为什么由 customer-service 执行而不是 tenant-service：脚本的运行期副本、SchemaGuard、
 * 迁移执行器都在这个服务里；控制面只负责"下发 + 留痕"，数据面负责"真的执行"。
 * 两边各记一份（tenant_db.tenant_schema_upgrade / customer_db.schema_upgrade_log），
 * 排查时能对上"平台说发过"和"库里真的跑过"。</p>
 */
@RestController
@InternalCustomerEndpoint(Caller.TENANT)
@RequestMapping("/api/customer/internal/tenant-schema")
public class InternalTenantSchemaController {

    private final TenantSchemaUpgradeService upgradeService;

    public InternalTenantSchemaController(TenantSchemaUpgradeService upgradeService) {
        this.upgradeService = upgradeService;
    }

    /**
     * 脚本清单（平台侧展示"最新版本是第几版、一共有哪些脚本"）
     */
    @GetMapping("/catalog")
    @InternalCustomerEndpoint(value = Caller.TENANT, tenantScoped = false)
    public ApiResponse<SchemaCatalogVO> catalog() {
        return ApiResponse.ok(new SchemaCatalogVO(TenantSchemaCatalog.LATEST_VERSION,
                TenantSchemaCatalog.STEPS));
    }

    /**
     * 版本状态：当前版本、最新版本、待执行脚本（平台侧详情页用）
     */
    @GetMapping("/status")
    public ApiResponse<TenantSchemaStatusVO> status(
            @RequestAttribute(CustomerInternalCall.ATTRIBUTE) CustomerInternalCall call,
            @RequestParam String tenantCode,
            @RequestParam(required = false) String targetSchema
    ) {
        String schema = targetSchema == null || targetSchema.isBlank()
                ? TenantSchemaUpgradeService.SHARED_SCHEMA : targetSchema;
        return call.inTenant(tenantCode, () -> {
            List<Step> pending = upgradeService.pending(tenantCode, schema);
            return ApiResponse.ok(new TenantSchemaStatusVO(tenantCode, schema,
                    upgradeService.currentVersion(tenantCode, schema),
                    TenantSchemaCatalog.LATEST_VERSION, pending));
        });
    }

    /**
     * 执行升级（幂等：已是最新版本直接返回）
     */
    @PostMapping("/upgrade")
    public ApiResponse<UpgradeResult> upgrade(
            @RequestAttribute(CustomerInternalCall.ATTRIBUTE) CustomerInternalCall call,
            @RequestParam String tenantCode,
            @RequestParam(required = false) String targetSchema,
            @RequestParam(required = false) Integer toVersion
    ) {
        return call.inTenant(tenantCode,
                () -> ApiResponse.ok(upgradeService.upgrade(tenantCode, targetSchema, toVersion, "platform")));
    }
}
