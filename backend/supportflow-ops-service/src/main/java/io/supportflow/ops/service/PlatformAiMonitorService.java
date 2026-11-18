package io.supportflow.ops.service;

import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.supportflow.ops.dto.AiLlmStatusVO;
import io.supportflow.ops.dto.AiMonitorVO;
import io.supportflow.ops.dto.AiUsageMetricsVO;
import io.supportflow.ops.internal.AiMetricsClient;
import org.springframework.stereotype.Service;

/**
 * 平台「AI 监控」的用量与成本块：调用量 / token / 成本 / 耗时 / 成功率。
 *
 * <p>数据在 AI 服务的 ai_db（ai_usage_record），这里只做两件事：
 * ① 把"是不是平台账号"卡住（AI 服务本身不做鉴权，不能让它直接对公网开）；
 * ② 原样转出聚合结果，前端不直连 9100。</p>
 */
@Service
public class PlatformAiMonitorService {

    /** 默认看近 7 天，最多 90 天 */
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;

    private final AiMetricsClient aiMetricsClient;

    public PlatformAiMonitorService(AiMetricsClient aiMetricsClient) {
        this.aiMetricsClient = aiMetricsClient;
    }

    /** AI 调用与成本监控（近 N 天调用量 / Token / 成本 / 按天分布 + 计量状态）。 */
    public AiMonitorVO metrics(LoginUser user, Integer days) {
        if (user.userType() != 1) {
            throw new BizException(40302, "只有平台账号能看 AI 调用与成本");
        }
        int span = days == null || days < 1 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        AiUsageMetricsVO metrics = aiMetricsClient.metrics(span);
        // 计量状态：真模型已配置 / 未配密钥只能降级。页面靠它决定怎么解释"0 调用"
        AiLlmStatusVO status = aiMetricsClient.status();
        // AI 服务没起来 / 没配密钥时给出明确信号，页面显示"暂无数据"而不是空白
        return new AiMonitorVO(
                metrics == null || metrics.days() == null ? span : metrics.days(),
                metrics == null ? null : metrics.from(),
                metrics == null ? null : metrics.to(),
                metrics == null ? null : metrics.overview(),
                metrics == null ? null : metrics.daily(),
                metrics == null ? null : metrics.byModel(),
                metrics == null ? null : metrics.byScene(),
                metrics == null ? null : metrics.topTenants(),
                metrics == null ? null : metrics.lastRealCallAt(),
                status, metrics != null);
    }
}
