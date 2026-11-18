package io.supportflow.billing.service;

import io.supportflow.billing.dto.ActiveTenants;
import io.supportflow.billing.dto.BatchGenerateVO;
import io.supportflow.billing.dto.SkippedTenantVO;
import io.supportflow.billing.entity.BillingStatement;
import io.supportflow.billing.internal.TenantPlanClient;
import io.supportflow.common.audit.AuditLogClient;
import io.supportflow.common.auth.LoginUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量出账：给「所有活跃租户」生成某个账期的账单。
 *
 * <p>为什么要单独一个类而不是在 {@link BillingService} 里加个循环：
 * {@code generateStatement} 上有 {@code @Transactional}，同类内部调用不经过 Spring 代理，
 * 事务不会生效。拆出来由它注入 {@code BillingService} 再逐户调用，每一户是独立事务——
 * 一家失败不影响其它家，也不会把整批回滚掉。</p>
 *
 * <p>单个租户失败（例如"该账期已结算，不能重算"）只记进结果里，不抛异常：
 * 月度出账任务不能因为一家已经结算过的租户就整批停摆。</p>
 */
@Service
public class BillingStatementBatchService {

    private static final Logger log = LoggerFactory.getLogger(BillingStatementBatchService.class);

    private final BillingService billingService;
    private final TenantPlanClient tenantPlanClient;
    /** 审计日志：批量出账一次动很多家，记一条"批量"就够（每张账单的细节在账单表里） */
    private final AuditLogClient auditLogClient;

    public BillingStatementBatchService(BillingService billingService, TenantPlanClient tenantPlanClient,
                                        AuditLogClient auditLogClient) {
        this.billingService = billingService;
        this.tenantPlanClient = tenantPlanClient;
        this.auditLogClient = auditLogClient;
    }

    /**
     * 给所有活跃租户生成 {@code cycleKey} 账期的账单。
     *
     * @return 结果汇总：账期、租户总数、成功清单、跳过清单（含原因）
     */
    public BatchGenerateVO generateForActiveTenants(LoginUser operator, String cycleKey) {
        ActiveTenants active = tenantPlanClient.activeTenants();
        List<String> tenants = active.codes();
        List<String> generated = new ArrayList<>();
        List<SkippedTenantVO> skipped = new ArrayList<>();
        for (String tenantCode : tenants) {
            try {
                BillingStatement statement = billingService.generateStatement(operator, tenantCode, cycleKey, null);
                generated.add(statement.getStatementNo());
            } catch (Exception e) {
                // 已结算 / 没套餐 / 数据异常：记原因继续下一家
                skipped.add(new SkippedTenantVO(tenantCode, String.valueOf(e.getMessage())));
                log.warn("批量出账跳过 tenant={} cycle={} reason={}", tenantCode, cycleKey, e.getMessage());
            }
        }
        log.info("批量出账完成 cycle={} 租户={} 成功={} 跳过={}",
                cycleKey, tenants.size(), generated.size(), skipped.size());
        auditLogClient.record(operator, "计费与账单", "批量出账", "账期", cycleKey,
                active.error() == null, active.error() == null
                        ? "账期 " + cycleKey + "：活跃租户 " + tenants.size()
                                + " 个，生成账单 " + generated.size() + " 张，跳过 " + skipped.size() + " 个"
                        : "账期 " + cycleKey + "：拿不到活跃租户清单 —— " + active.error());
        // 拿不到租户清单时把原因带出去：前端要能区分"调用失败"和"平台里真的没有租户"
        return new BatchGenerateVO(cycleKey, tenants.size(), generated.size(), generated,
                skipped.size(), skipped, active.error());
    }
}
