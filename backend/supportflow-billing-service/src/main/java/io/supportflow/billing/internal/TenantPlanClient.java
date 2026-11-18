package io.supportflow.billing.internal;

import io.supportflow.billing.dto.ActiveTenants;
import io.supportflow.billing.dto.PlanInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * tenant-service 内部客户端：支付成功后把套餐与到期时间写回租户，并同步限流配额。
 *
 * <p>为什么由 billing 主动通知 tenant：设计文档里这条链路就是这么定的——
 * "billing 下单支付 → 事件通知 → tenant 更新套餐/到期时间"。
 * 套餐一变，租户的 QPS/日额度（tenant_quota）也跟着变，两处口径一次写清。</p>
 */
@Component
public class TenantPlanClient {

    private static final Logger log = LoggerFactory.getLogger(TenantPlanClient.class);

    private final RestClient restClient;

    public TenantPlanClient(@Value("${supportflow.tenant.internal-base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * 查租户当前套餐（权威值在 tenant_db）：用量与账单都以它为准。
     *
     * <p>拿不到就返回 {@link PlanInfo#none()}，调用方按"未开通"处理——
     * 计费展示不能因为 tenant-service 抖一下就报错。</p>
     */
    @SuppressWarnings("unchecked")
    public PlanInfo planOf(String tenantCode) {
        try {
            Map<String, Object> body = restClient.get()
                    .uri("/api/tenant/internal/tenants/{tenantCode}/plan", tenantCode)
                    .retrieve()
                    .body(Map.class);
            Map<String, Object> data = body == null ? null : (Map<String, Object>) body.get("data");
            if (data == null) {
                return PlanInfo.none();
            }
            Object planId = data.get("planId");
            return new PlanInfo(
                    planId == null ? null : Long.valueOf(String.valueOf(planId)),
                    data.get("expireTime") == null ? null : LocalDateTime.parse(
                            String.valueOf(data.get("expireTime")).substring(0, 19)),
                    data.get("status") == null ? null : Integer.valueOf(String.valueOf(data.get("status"))));
        } catch (Exception e) {
            log.warn("查询租户当前套餐失败 tenant={} error={}", tenantCode, e.getMessage());
            return PlanInfo.none();
        }
    }

    /**
     * 活跃租户（已开通 + 有套餐）——月度出账任务要遍历的清单。
     *
     * <p>拿不到就带上原因返回：宁可这一次不出账，也不能凭"猜出来的租户列表"去生成账单，
     * 下一次定时任务会补上。</p>
     *
     * <p><b>为什么不直接返回空列表</b>：调用失败和"真的没有租户"是两件事，
     * 混成一个空列表会让页面提示"没有活跃租户"，把人往错误的方向带
     * （实际原因常常是 tenant-service 没起、或升级后忘了重启、或接口 404）。
     * 踩过这个坑，所以这里把 error 一起带出去。</p>
     */
    @SuppressWarnings("unchecked")
    public ActiveTenants activeTenants() {
        try {
            Map<String, Object> body = restClient.get()
                    .uri("/api/tenant/internal/tenants/active")
                    .retrieve()
                    .body(Map.class);
            List<Map<String, Object>> data = body == null ? null : (List<Map<String, Object>>) body.get("data");
            if (data == null) {
                return new ActiveTenants(List.of(), null);
            }
            List<String> codes = data.stream()
                    .map(item -> item.get("tenantCode"))
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .toList();
            return new ActiveTenants(codes, null);
        } catch (Exception e) {
            String reason = "调用 tenant-service /api/tenant/internal/tenants/active 失败：" + e.getMessage()
                    + "（检查 tenant-service 是否已启动、以及升级后是否已重启）";
            log.warn("查询活跃租户列表失败：{}", reason);
            return new ActiveTenants(List.of(), reason);
        }
    }

    /**
     * 更新租户套餐 + 同步限流配额；失败只记日志（账单已支付，不能因为通知失败回滚）
     */
    public boolean applyPlan(String tenantCode, long planId, String planName, int cycle,
                             BigDecimal amount, int qpsLimit, int dailyRequestLimit,
                             int dailySessionLimit, int aiDailyLimit) {
        return applyPlan(tenantCode, planId, planName, cycle, amount, qpsLimit,
                dailyRequestLimit, dailySessionLimit, aiDailyLimit, null);
    }

    /**
     * 带试用天数的版本：开免费版试用时用，让 tenant 按"今天 + N 天"设到期时间
     */
    public boolean applyPlan(String tenantCode, long planId, String planName, int cycle,
                             BigDecimal amount, int qpsLimit, int dailyRequestLimit,
                             int dailySessionLimit, int aiDailyLimit, Integer trialDays) {
        try {
            restClient.post()
                    .uri("/api/tenant/internal/tenants/{tenantCode}/plan", tenantCode)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PlanBody(planId, planName, cycle, amount,
                            qpsLimit, dailyRequestLimit, dailySessionLimit, aiDailyLimit, trialDays))
                    .retrieve()
                    .toBodilessEntity();
            log.info("租户套餐已更新 tenant={} plan={} cycle={}", tenantCode, planName, cycle);
            return true;
        } catch (Exception e) {
            log.warn("租户套餐更新失败（账单已支付，需人工核对）tenant={} plan={} error={}",
                    tenantCode, planName, e.getMessage());
            return false;
        }
    }

    /**
     * 用量上报（交给 tenant-service 落 tenant_usage_daily，平台页展示用）
     */
    public void reportUsage(String tenantCode, long requests, long rejected) {
        try {
            restClient.post()
                    .uri("/api/tenant/internal/isolation/usage/report")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(Map.of(
                            "tenantCode", tenantCode,
                            "statDate", LocalDate.now().toString(),
                            "requestCount", requests,
                            "rejectedCount", rejected,
                            "sessionCount", 0,
                            "aiCallCount", 0)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.debug("用量转发失败 tenant={} error={}", tenantCode, e.getMessage());
        }
    }

    public record PlanBody(long planId, String planName, int cycle, BigDecimal amount,
                           int qpsLimit, int dailyRequestLimit,
                           int dailySessionLimit, int aiDailyLimit, Integer trialDays) {
    }
}
