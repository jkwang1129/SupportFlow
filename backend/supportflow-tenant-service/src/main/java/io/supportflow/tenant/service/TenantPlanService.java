package io.supportflow.tenant.service;

import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.tenant.dto.ActiveTenantVO;
import io.supportflow.tenant.dto.AppliedPlanVO;
import io.supportflow.tenant.dto.PlanBody;
import io.supportflow.tenant.dto.TenantPlanVO;
import io.supportflow.tenant.entity.Tenant;
import io.supportflow.tenant.entity.TenantQuota;
import io.supportflow.tenant.mapper.TenantMapper;
import io.supportflow.tenant.mapper.TenantQuotaMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 租户套餐：billing 支付成功后的套餐写回 + 配额同步，以及账单要用的租户清单。
 *
 * <p>为什么套餐一变就要同步配额：套餐是"配额 + 价格"的唯一来源。
 * 如果买了专业版却还按标准版的 QPS 限流，用户会觉得"花了钱没生效"；
 * 两处口径一次写清，就不会出现"账单按专业版收钱、网关按标准版限流"。</p>
 */
@Service
public class TenantPlanService {

    private static final Logger log = LoggerFactory.getLogger(TenantPlanService.class);

    /** 周期码：1-年付、2-月付 */
    private static final int CYCLE_YEARLY = 1;

    /** 内部写回套餐这一动作的操作人，写进 create_by / update_by 便于追责 */
    private static final String OPERATOR = "billing-service";

    private final TenantMapper tenantMapper;
    private final TenantQuotaMapper quotaMapper;
    private final SnowflakeIdGenerator idGenerator;

    public TenantPlanService(TenantMapper tenantMapper,
                             TenantQuotaMapper quotaMapper,
                             SnowflakeIdGenerator idGenerator) {
        this.tenantMapper = tenantMapper;
        this.quotaMapper = quotaMapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 查租户当前套餐（只读）。
     *
     * <p>billing-service 算用量与账单时必须知道"这个租户现在到底是什么套餐"，
     * 权威值在 tenant_db 的 {@code tenant.plan_id}（billing 库只存"买过什么订单"）。
     * 之前靠前端把 planId 传过来，结果前端不知道就显示"未开通"。</p>
     */
    public TenantPlanVO currentPlan(String tenantCode) {
        Tenant tenant = requireTenant(tenantCode);
        return new TenantPlanVO(tenant.getTenantCode(), tenant.getPlanId(),
                tenant.getExpireTime(), tenant.getStatus(), tenant.getIsolationMode());
    }

    /**
     * 活跃租户列表（已开通 + 有套餐）：billing 的月度出账任务按它逐个生成账单。
     *
     * <p>为什么不让 billing 自己去猜租户：租户的权威列表在 tenant_db，
     * billing 库只有"买过什么订单"。让它遍历自己的订单表会漏掉"试用中的租户"，
     * 也会把已注销的租户算进来。这里只返回 status=1 且 plan_id 不为空的租户。</p>
     */
    public List<ActiveTenantVO> activeTenants() {
        List<Tenant> tenants = tenantMapper.selectList(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getDeleted, false)
                .eq(Tenant::getStatus, 1)
                .isNotNull(Tenant::getPlanId)
                .orderByAsc(Tenant::getTenantCode));
        List<ActiveTenantVO> list = new ArrayList<>(tenants.size());
        for (Tenant tenant : tenants) {
            list.add(new ActiveTenantVO(tenant.getTenantCode(), tenant.getPlanId(),
                    tenant.getExpireTime()));
        }
        return list;
    }

    /**
     * 全部租户编码（状态正常、未删除）——平台公告要推送给<b>所有</b>租户的通知中心。
     *
     * <p>和 {@link #activeTenants()} 的区别：那个只返回"有套餐"的租户（出账用），
     * 这个返回所有正常租户（公告 / 全员通知用）。</p>
     */
    public List<String> tenantCodes() {
        return tenantMapper.selectList(Wrappers.<Tenant>lambdaQuery()
                        .eq(Tenant::getDeleted, false)
                        .eq(Tenant::getStatus, 1)
                        .orderByAsc(Tenant::getTenantCode))
                .stream().map(Tenant::getTenantCode).toList();
    }

    /**
     * 更新租户套餐 + 到期时间 + 限流配额（billing 支付成功后的回调）。
     *
     * <p>到期时间两种算法：配了试用天数就"今天 + N 天"（试用就是给一段确定的时间），
     * 否则按周期（年付 +1 年、月付 +1 月）。配额一并写回，避免账单与限流两套口径。</p>
     */
    @Transactional
    public AppliedPlanVO applyPlan(String tenantCode, PlanBody body) {
        Tenant tenant = requireTenant(tenantCode);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expire = body.trialDays() != null && body.trialDays() > 0
                ? now.plusDays(body.trialDays())
                : (body.cycle() == CYCLE_YEARLY ? now.plusYears(1) : now.plusMonths(1));

        Tenant update = new Tenant();
        update.setId(tenant.getId());
        update.setPlanId(body.planId());
        update.setExpireTime(expire);
        update.setUpdateTime(now);
        update.setEditor(OPERATOR);
        tenantMapper.updateById(update);

        // 同步限流配额：QPS / 日请求 / 日会话 / 日 AI 调用
        TenantQuota quota = quotaMapper.selectOne(Wrappers.<TenantQuota>lambdaQuery()
                .eq(TenantQuota::getTenantCode, tenantCode)
                .eq(TenantQuota::getDeleted, false));
        if (quota == null) {
            quotaMapper.insert(TenantQuota.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenantCode)
                    .qpsLimit(body.qpsLimit())
                    .dailyRequestLimit(body.dailyRequestLimit())
                    .dailySessionLimit(body.dailySessionLimit())
                    .aiDailyLimit(body.aiDailyLimit())
                    .enabled(true)
                    .remark("随套餐开通：" + body.planName())
                    .createTime(now)
                    .updateTime(now)
                    .creator(OPERATOR)
                    .deleted(false)
                    .build());
        } else {
            quota.setQpsLimit(body.qpsLimit());
            quota.setDailyRequestLimit(body.dailyRequestLimit());
            quota.setDailySessionLimit(body.dailySessionLimit());
            quota.setAiDailyLimit(body.aiDailyLimit());
            quota.setRemark("随套餐变更：" + body.planName());
            quota.setUpdateTime(now);
            quota.setEditor(OPERATOR);
            quotaMapper.updateById(quota);
        }
        log.info("租户套餐已生效 tenant={} plan={} 到期={} QPS={}",
                tenantCode, body.planName(), expire, body.qpsLimit());

        return new AppliedPlanVO(tenantCode, body.planId(), body.planName(),
                expire, body.qpsLimit());
    }

    /** 取租户，不存在直接抛业务异常——四个内部接口的前置校验都一样，抽出来只写一次 */
    private Tenant requireTenant(String tenantCode) {
        Tenant tenant = tenantMapper.selectOne(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getTenantCode, tenantCode)
                .eq(Tenant::getDeleted, false));
        if (tenant == null) {
            throw new BizException(40401, "租户不存在：" + tenantCode);
        }
        return tenant;
    }
}
