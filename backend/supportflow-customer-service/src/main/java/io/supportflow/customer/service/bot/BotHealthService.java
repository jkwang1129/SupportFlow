package io.supportflow.customer.service.bot;

import io.supportflow.customer.dto.bot.BotAiHealthVO;
import io.supportflow.customer.dto.bot.BotHealthVO;
import io.supportflow.customer.dto.bot.RealtimeHealthVO;
import io.supportflow.customer.internal.BotAiClient;
import io.supportflow.customer.internal.RealtimeNotifyClient;
import org.springframework.stereotype.Service;

/**
 * 「智能客服不回话」的一键体检。
 *
 * <p>机器人回一句话要跨三个服务：customer-service（落库与编排）→ supportflow-ai（意图/情绪/回答）
 * → supportflow-realtime-service（把回复推给客户）。任何一环没起、或者是旧版本，客户侧看到的
 * 现象都一模一样：<b>发出去没人理</b>。日志散在三个服务里，靠翻日志定位很慢。</p>
 *
 * <p>所以这里把"该通的三条路"一次性探完，并直接给出下一步该做什么：
 * 开关是不是关着、AI 有没有 /agent/brain、实时网关有没有 /internal/message。</p>
 */
@Service
public class BotHealthService {

    private final BotBrainService botBrainService;
    private final BotAiClient aiClient;
    private final RealtimeNotifyClient realtimeClient;

    public BotHealthService(BotBrainService botBrainService,
                            BotAiClient aiClient,
                            RealtimeNotifyClient realtimeClient) {
        this.botBrainService = botBrainService;
        this.aiClient = aiClient;
        this.realtimeClient = realtimeClient;
    }

    /** 体检报告：两个总开关 + AI 链路 + 实时链路 + 一句话结论。 */
    public BotHealthVO health(String tenantCode) {
        BotAiHealthVO ai = aiClient.health();
        RealtimeHealthVO realtime = realtimeClient.health();
        return new BotHealthVO(tenantCode, botBrainService.enabled(),
                botBrainService.receptionEnabled(), ai, realtime,
                hint(ai, realtime));
    }

    /**
     * 一句话结论：按"最可能的原因"排优先级，直接告诉用户下一步干什么。
     */
    private String hint(BotAiHealthVO ai, RealtimeHealthVO realtime) {
        if (!botBrainService.enabled()) {
            return "机器人总开关是关的（supportflow.bot.enabled=false），客户进线会直接排人工队列";
        }
        if (!botBrainService.receptionEnabled()) {
            return "「机器人首轮接待」是关的（supportflow.bot.reception-enabled=false），"
                    + "客户进线直接进人工队列；想让它先接待就打开这个开关";
        }
        if (!Boolean.TRUE.equals(ai.reachable())) {
            return ai.hint() == null
                    ? "连不上 supportflow-ai：机器人拿不到回复，客户会看到「正在为您转接人工客服」"
                    : ai.hint();
        }
        if (!Boolean.TRUE.equals(realtime.reachable())) {
            return realtime.hint() == null
                    ? "连不上实时网关：机器人回复能落库但推不到客户页面（客户刷新才能看到）"
                    : realtime.hint();
        }
        return "链路正常：机器人可以接待。若客户仍看不到回复，"
                + "确认会话状态是 2-机器人接待（已转人工或已被坐席接手的会话，机器人不会再回话）";
    }
}
