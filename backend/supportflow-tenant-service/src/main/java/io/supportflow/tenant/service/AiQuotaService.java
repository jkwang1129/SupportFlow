package io.supportflow.tenant.service;

import io.supportflow.tenant.dto.AiQuotaItemVO;
import io.supportflow.tenant.dto.AiQuotaSummaryVO;
import io.supportflow.tenant.dto.AiQuotaVO;
import io.supportflow.tenant.internal.AiUsageClient;
import io.supportflow.tenant.mapper.AiQuotaMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 租户 AI 配额视图（平台「AI 监控」的配额块）。
 *
 * <p>两个数据源拼在一起：</p>
 * <ol>
 *   <li><b>限额</b>：tenant_db.tenant_quota（每天允许多少次 AI 调用）——本服务自己的库；</li>
 *   <li><b>今日用量</b>：AI 服务的 ai_usage_record（真实调用计数）——通过内部接口取，
 *       不跨库读别人的表。</li>
 * </ol>
 *
 * <p>为什么用量取 AI 服务的真实调用数，而不是网关上报的 ai_call_count：
 * 网关那个计数目前恒为 0（限流只数请求），拿它算配额会出现"天天用满却显示 0%"。
 * 真实调用次数只有真正调模型的地方知道。</p>
 *
 * <p>使用率口径：今日调用 ÷ 日配额；配额为 0 表示不限，使用率记 0 并标"不限"。</p>
 */
@Service
public class AiQuotaService {

    /** 使用率到达这个比例就算"接近上限"，平台要提前提醒租户 */ 
    private static final int WARN_PERCENT = 80;

    private final AiQuotaMapper quotaMapper;
    private final AiUsageClient aiUsageClient;

    public AiQuotaService(AiQuotaMapper quotaMapper, AiUsageClient aiUsageClient) {
        this.quotaMapper = quotaMapper;
        this.aiUsageClient = aiUsageClient;
    }

    public AiQuotaVO quotaView() {
        LocalDate today = LocalDate.now();
        Map<String, Map<String, Object>> usage = aiUsageClient.tenantUsage(today);

        List<Map<String, Object>> quotas = quotaMapper.selectQuotaView();
        List<AiQuotaItemVO> items = new ArrayList<>(quotas.size());
        int unlimited = 0;
        int warning = 0;
        int exceeded = 0;
        long todayCalls = 0;
        for (Map<String, Object> quota : quotas) {
            String tenantCode = String.valueOf(quota.get("tenant_code"));
            Map<String, Object> raw = usage.getOrDefault(tenantCode, Map.of());
            int calls = intValue(raw.get("calls"));
            int limit = intValue(quota.get("ai_daily_limit"));
            int percent = limit <= 0 ? 0 : (int) Math.round(calls * 100.0 / limit);
            int status;
            if (limit <= 0) {
                unlimited++;
                status = 1;
            } else if (calls > limit) {
                exceeded++;
                status = 3;
            } else if (percent >= WARN_PERCENT) {
                warning++;
                status = 2;
            } else {
                status = 1;
            }
            todayCalls += calls;

            items.add(new AiQuotaItemVO(tenantCode,
                    quota.get("company_name") == null
                            ? tenantCode : String.valueOf(quota.get("company_name")),
                    limit, calls, intValue(raw.get("tokens")), decimalValue(raw.get("cost")),
                    intValue(raw.get("failedCalls")), percent, status, statusText(status, limit),
                    intValue(quota.get("tenant_status")),
                    intValue(quota.get("daily_request_limit")),
                    Boolean.TRUE.equals(quota.get("quota_enabled"))));
        }

        AiQuotaSummaryVO summary = new AiQuotaSummaryVO(today.toString(), quotas.size(),
                (int) todayCalls, warning, exceeded, unlimited, !usage.isEmpty());
        return new AiQuotaVO(summary, items, WARN_PERCENT);
    }

    private static String statusText(int status, int limit) {
        if (limit <= 0) {
            return "不限";
        }
        return switch (status) {
            case 3 -> "已超限";
            case 2 -> "接近上限";
            default -> "正常";
        };
    }

    private static int intValue(Object raw) {
        if (raw == null) {
            return 0;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double decimalValue(Object raw) {
        if (raw == null) {
            return 0d;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }
}
